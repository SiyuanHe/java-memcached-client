// Copyright (c) 2006  Dustin Sallings <dustin@spy.net>

package net.spy.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.spy.SpyObject;
import net.spy.memcached.ops.GetOperation;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationException;
import net.spy.memcached.ops.OptimizedGet;

/**
 * Connection to a cluster of memcached servers.
 */
public class MemcachedConnection extends SpyObject {
	// The number of empty selects we'll allow before taking action.  It's too
	// easy to write a bug that causes it to loop uncontrollably.  This helps
	// find those bugs and often works around them.
	private static final int EXCESSIVE_EMPTY = 100;
	// maximum amount of time to wait between reconnect attempts
	private static final long MAX_DELAY = 30000;
	// Maximum number of sequential errors before reconnecting.
	private static final int EXCESSIVE_ERRORS = 1;

	private volatile boolean shutDown=false;
	// If true, get optimization will collapse multiple sequential get ops
	private boolean optimizeGets=true;
	private Selector selector=null;
	private QueueAttachment[] connections=null;
	private int emptySelects=0;
	// AddedQueue is used to track the QueueAttachments for which operations
	// have recently been queued.
	private final ConcurrentLinkedQueue<QueueAttachment> addedQueue;
	// reconnectQueue contains the attachments that need to be reconnected
	// The key is the time at which they are eligible for reconnect
	private final SortedMap<Long, QueueAttachment> reconnectQueue;

	/**
	 * Construct a memcached connection.
	 *
	 * @param bufSize the size of the buffer used for reading from the server
	 * @param f the factory that will provide an operation queue
	 * @param a the addresses of the servers to connect to
	 *
	 * @throws IOException if a connection attempt fails early
	 */
	public MemcachedConnection(int bufSize, ConnectionFactory f,
			List<InetSocketAddress> a)
		throws IOException {
		reconnectQueue=new TreeMap<Long, QueueAttachment>();
		addedQueue=new ConcurrentLinkedQueue<QueueAttachment>();
		selector=Selector.open();
		connections=new QueueAttachment[a.size()];
		int cons=0;
		for(SocketAddress sa : a) {
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			QueueAttachment qa=new QueueAttachment(sa, ch, bufSize,
				f.createOperationQueue(), f.createOperationQueue(),
				f.createOperationQueue());
			qa.which=cons;
			int ops=0;
			if(ch.connect(sa)) {
				getLogger().info("Connected to %s immediately", qa);
				qa.reconnectAttempt=0;
				assert ch.isConnected();
			} else {
				getLogger().info("Added %s to connect queue", qa);
				ops=SelectionKey.OP_CONNECT;
			}
			qa.sk=ch.register(selector, ops, qa);
			assert ch.isConnected()
				|| qa.sk.interestOps() == SelectionKey.OP_CONNECT
				: "Not connected, and not wanting to connect";
			connections[cons++]=qa;
		}
	}

	/**
	 * Enable or disable get optimization.
	 *
	 * When enabled (default), multiple sequential gets are collapsed into one.
	 */
	public void setGetOptimization(boolean to) {
		optimizeGets=to;
	}

	private boolean selectorsMakeSense() {
		for(QueueAttachment qa : connections) {
			if(qa.sk.isValid()) {
				if(qa.channel.isConnected()) {
					int sops=qa.sk.interestOps();
					int expected=0;
					if(qa.hasReadOp()) {
						expected |= SelectionKey.OP_READ;
					}
					if(qa.hasWriteOp()) {
						expected |= SelectionKey.OP_WRITE;
					}
					if(qa.toWrite > 0) {
						expected |= SelectionKey.OP_WRITE;
					}
					assert sops == expected : "Invalid ops:  "
						+ qa + ", expected " + expected + ", got " + sops;
				} else {
					int sops=qa.sk.interestOps();
					assert sops == SelectionKey.OP_CONNECT
					: "Not connected, and not watching for connect: "
						+ sops;
				}
			}
		}
		getLogger().debug("Checked the selectors.");
		return true;
	}

	/**
	 * MemcachedClient calls this method to handle IO over the connections.
	 */
	@SuppressWarnings("unchecked")
	public void handleIO() throws IOException {
		if(shutDown) {
			throw new IOException("No IO while shut down");
		}

		// Deal with all of the stuff that's been added, but may not be marked
		// writable.
		handleInputQueue();
		getLogger().debug("Done dealing with queue.");

		long delay=0;
		if(!reconnectQueue.isEmpty()) {
			long now=System.currentTimeMillis();
			long then=reconnectQueue.firstKey();
			delay=Math.max(then-now, 1);
		}
		getLogger().debug("Selecting with delay of %sms", delay);
		assert selectorsMakeSense() : "Selectors don't make sense.";
		int selected=selector.select(delay);
		Set<SelectionKey> selectedKeys=selector.selectedKeys();

		if(selectedKeys.isEmpty()) {
			getLogger().debug("No selectors ready, interrupted: "
					+ Thread.interrupted());
			if(++emptySelects > EXCESSIVE_EMPTY) {
				for(SelectionKey sk : selector.keys()) {
					getLogger().info("%s has %s, interested in %s",
							sk, sk.readyOps(), sk.interestOps());
					if(sk.readyOps() != 0) {
						getLogger().info("%s has a ready op, handling IO", sk);
						handleIO(sk);
					} else {
						queueReconnect((QueueAttachment)sk.attachment());
					}
				}
				assert emptySelects < EXCESSIVE_EMPTY + 10
					: "Too many empty selects";
			}
		} else {
			getLogger().debug("Selected %d, selected %d keys",
					selected, selectedKeys.size());
			emptySelects=0;
			for(SelectionKey sk : selectedKeys) {
				getLogger().debug(
						"Got selection key:  %s (r=%s, w=%s, c=%s, op=%s)",
						sk, sk.isReadable(), sk.isWritable(),
						sk.isConnectable(), sk.attachment());
				handleIO(sk);
			} // for each selector
			selectedKeys.clear();
		}

		if(!reconnectQueue.isEmpty()) {
			attemptReconnects();
		}
	}

	// Handle any requests that have been made against the client.
	private void handleInputQueue() {
		if(!addedQueue.isEmpty()) {
			getLogger().debug("Handling queue");
			// If there's stuff in the added queue.  Try to process it.
			Collection<QueueAttachment> toAdd=new HashSet<QueueAttachment>();
			try {
				QueueAttachment qa=null;
				while((qa=addedQueue.remove()) != null) {
					boolean readyForIO=false;
					if(qa.channel != null && qa.channel.isConnected()) {
						Operation op=qa.getCurrentWriteOp();
						if(op != null) {
							readyForIO=true;
							getLogger().debug("Handling queued write %s", qa);
						}
					} else {
						toAdd.add(qa);
					}
					qa.copyInputQueue();
					if(readyForIO) {
						try {
							if(qa.wbuf.hasRemaining()) {
								handleWrites(qa.sk, qa);
							}
						} catch(IOException e) {
							getLogger().warn("Exception handling write", e);
							queueReconnect(qa);
						}
					}
					fixupOps(qa);
				}
			} catch(NoSuchElementException e) {
				// out of stuff.
			}
			addedQueue.addAll(toAdd);
		}
	}

	// Handle IO for a specific selector.  Any IOException will cause a
	// reconnect
	private void handleIO(SelectionKey sk) {
		assert !sk.isAcceptable() : "We don't do accepting here.";
		QueueAttachment qa=(QueueAttachment)sk.attachment();
		if(sk.isConnectable()) {
			getLogger().info("Connection state changed for %s", sk);
			try {
				if(qa.channel.finishConnect()) {
					assert qa.channel.isConnected() : "Not connected.";
					qa.reconnectAttempt=0;
					addedQueue.offer(qa);
					if(qa.wbuf.hasRemaining()) {
						handleWrites(sk, qa);
					}
				} else {
					assert !qa.channel.isConnected() : "connected";
				}
			} catch(IOException e) {
				getLogger().warn("Problem handling connect", e);
				queueReconnect(qa);
			}
		} else {
			if(sk.isWritable()) {
				try {
					handleWrites(sk, qa);
				} catch (IOException e) {
					getLogger().info("IOException handling %s, reconnecting",
							qa.getCurrentWriteOp(), e);
					queueReconnect(qa);
				}
			}
			if(sk.isReadable()) {
				try {
					handleReads(sk, qa);
					qa.protocolErrors=0;
				} catch (OperationException e) {
					if(++qa.protocolErrors >= EXCESSIVE_ERRORS) {
						queueReconnect(qa);
					}
				} catch (IOException e) {
					getLogger().info("IOException handling %s, reconnecting",
							qa.getCurrentReadOp(), e);
					queueReconnect(qa);
				}
			}
		}
		fixupOps(qa);
	}

	private void handleWrites(SelectionKey sk, QueueAttachment qa)
		throws IOException {
		qa.fillWriteBuffer(optimizeGets);
		boolean canWriteMore=qa.toWrite > 0;
		while(canWriteMore) {
			int wrote=qa.channel.write(qa.wbuf);
			assert wrote >= 0 : "Wrote negative bytes?";
			qa.toWrite -= wrote;
			assert qa.toWrite >= 0
				: "toWrite went negative after writing " + wrote
					+ " bytes for " + qa;
			getLogger().debug("Wrote %d bytes", wrote);
			qa.fillWriteBuffer(optimizeGets);
			canWriteMore = wrote > 0 && qa.toWrite > 0;
		}
	}

	private void handleReads(SelectionKey sk, QueueAttachment qa)
		throws IOException {
		Operation currentOp = qa.getCurrentReadOp();
		int read=qa.channel.read(qa.rbuf);
		while(read > 0) {
			getLogger().debug("Read %d bytes", read);
			qa.rbuf.flip();
			while(qa.rbuf.remaining() > 0) {
				assert currentOp != null : "No read operation";
				currentOp.readFromBuffer(qa.rbuf);
				if(currentOp.getState() == Operation.State.COMPLETE) {
					getLogger().debug(
							"Completed read op: %s and giving the next %d bytes",
							currentOp, qa.rbuf.remaining());
					Operation op=qa.removeCurrentReadOp();
					assert op == currentOp
					: "Expected to pop " + currentOp + " got " + op;
					currentOp=qa.getCurrentReadOp();
				}
			}
			qa.rbuf.clear();
			read=qa.channel.read(qa.rbuf);
		}
	}

	private void fixupOps(QueueAttachment qa) {
		if(qa.sk.isValid()) {
			int iops=qa.getSelectionOps();
			getLogger().debug("Setting interested opts to %d", iops);
			qa.sk.interestOps(iops);
		} else {
			getLogger().debug("Selection key is not valid.");
		}
	}

	// Make a debug string out of the given buffer's values
	static String dbgBuffer(ByteBuffer b, int size) {
		StringBuilder sb=new StringBuilder();
		byte[] bytes=b.array();
		for(int i=0; i<size; i++) {
			char ch=(char)bytes[i];
			if(Character.isWhitespace(ch) || Character.isLetterOrDigit(ch)) {
				sb.append(ch);
			} else {
				sb.append("\\x");
				sb.append(Integer.toHexString(bytes[i] & 0xff));
			}
		}
		return sb.toString();
	}

	private void queueReconnect(QueueAttachment qa) {
		if(!shutDown) {
			getLogger().warn("Closing, and reopening %s, attempt %d.",
					qa, qa.reconnectAttempt);
			if(qa.sk != null) {
				qa.sk.cancel();
				assert !qa.sk.isValid() : "Cancelled selection key is valid";
			}
			qa.reconnectAttempt++;
			try {
				qa.channel.socket().close();
			} catch(IOException e) {
				getLogger().warn("IOException trying to close a socket", e);
			}
			qa.channel=null;

			long delay=Math.min((100*qa.reconnectAttempt) ^ 2, MAX_DELAY);

			reconnectQueue.put(System.currentTimeMillis() + delay, qa);

			// Need to do a little queue management.
			qa.setupResend();
		}
	}

	private void attemptReconnects() throws IOException {
		long now=System.currentTimeMillis();
		for(Iterator<QueueAttachment> i=
				reconnectQueue.headMap(now).values().iterator(); i.hasNext();) {
			QueueAttachment qa=i.next();
			i.remove();
			getLogger().info("Reconnecting %s", qa);
			SocketChannel ch=SocketChannel.open();
			ch.configureBlocking(false);
			int ops=0;
			if(ch.connect(qa.socketAddress)) {
				getLogger().info("Immediately reconnected to %s", qa);
				assert ch.isConnected();
			} else {
				ops=SelectionKey.OP_CONNECT;
			}
			qa.channel=ch;
			qa.sk=ch.register(selector, ops, qa);
		}
	}

	/**
	 * Get the number of connections currently handled.
	 */
	public int getNumConnections() {
		return connections.length;
	}

	/**
	 * Get the remote address of the socket with the given ID.
	 * 
	 * @param which which id
	 * @return the rmeote address
	 */
	public SocketAddress getAddressOf(int which) {
		return connections[which].socketAddress;
	}

	/**
	 * Add an operation to the given connection.
	 * 
	 * @param which the connection offset
	 * @param o the operation
	 */
	public void addOperation(int which, Operation o) {
		boolean placed=false;
		int pos=which;
		int loops=0;
		while(!placed) {
			assert loops < 3 : "Too many loops!";
			QueueAttachment qa=connections[pos];
			if(which == pos) {
				loops++;
			}
			if(qa.reconnectAttempt == 0 || loops > 1) {
				o.initialize();
				qa.addOp(o);
				addedQueue.offer(qa);
				Selector s=selector.wakeup();
				assert s == selector : "Wakeup returned the wrong selector.";
				getLogger().debug("Added %s to %d", o, which);
				placed=true;
			} else {
				if(++pos >= connections.length) {
					pos=0;
				}
			}
		}
	}

	/**
	 * Shut down all of the connections.
	 */
	public void shutdown() throws IOException {
		for(QueueAttachment qa : connections) {
			if(qa.channel != null) {
				qa.channel.close();
				qa.sk=null;
				if(qa.toWrite > 0) {
					getLogger().warn(
						"Shut down with %d bytes remaining to write",
							qa.toWrite);
				}
				getLogger().debug("Shut down channel %s", qa.channel);
			}
		}
		selector.close();
		getLogger().debug("Shut down selector %s", selector);
	}

	@Override
	public String toString() {
		StringBuilder sb=new StringBuilder();
		sb.append("{MemcachedConnection to");
		for(QueueAttachment qa : connections) {
			sb.append(" ");
			sb.append(qa.socketAddress);
		}
		sb.append("}");
		return sb.toString();
	}

	static class QueueAttachment extends SpyObject {
		public final SocketAddress socketAddress;
		public final ByteBuffer rbuf;
		public final ByteBuffer wbuf;
		private final BlockingQueue<Operation> writeQ;
		private final BlockingQueue<Operation> readQ;
		private final BlockingQueue<Operation> inputQueue;
		public int which=0;
		// This has been declared volatile so it can be used as an availability
		// indicator.
		public volatile int reconnectAttempt=1;
		public SocketChannel channel;
		public int toWrite=0;
		private GetOperation getOp=null;
		public SelectionKey sk=null;

		// Count sequential protocol errors.
		public int protocolErrors=0;

		public QueueAttachment(SocketAddress sa, SocketChannel c, int bufSize,
				BlockingQueue<Operation> rq, BlockingQueue<Operation> wq,
				BlockingQueue<Operation> iq) {
			super();
			assert sa != null : "No SocketAddress";
			assert c != null : "No SocketChannel";
			assert bufSize > 0 : "Invalid buffer size: " + bufSize;
			assert rq != null : "No operation read queue";
			assert wq != null : "No operation write queue";
			assert iq != null : "No input queue";
			socketAddress=sa;
			channel=c;
			rbuf=ByteBuffer.allocate(bufSize);
			wbuf=ByteBuffer.allocate(bufSize);
			wbuf.clear();
			readQ=rq;
			writeQ=wq;
			inputQueue=iq;
		}

		public void copyInputQueue() {
			Collection<Operation> tmp=new ArrayList<Operation>();
			inputQueue.drainTo(tmp);
			writeQ.addAll(tmp);
		}


		public void setupResend() {
			// First, reset the current write op.
			Operation op=getCurrentWriteOp();
			if(op != null) {
				op.getBuffer().reset();
			}
			// Now cancel all the pending read operations.  Might be better to
			// to requeue them.
			while(hasReadOp()) {
				op=removeCurrentReadOp();
				getLogger().warn("Discarding partially completed op: %s", op);
				op.cancel();
			}

			wbuf.clear();
			rbuf.clear();
			toWrite=0;
			protocolErrors=0;
		}

		// Prepare the pending operations.  Return true if there are any pending
		// ops
		private boolean preparePending() {
			// Copy the input queue into the write queue.
			copyInputQueue();

			// Now check the ops
			Operation nextOp=getCurrentWriteOp();
			while(nextOp != null && nextOp.isCancelled()) {
				getLogger().info("Removing cancelled operation: %s", nextOp);
				removeCurrentWriteOp();
				nextOp=getCurrentWriteOp();
			}
			return nextOp != null;
		}

		public void fillWriteBuffer(boolean optimizeGets) {
			if(toWrite == 0) {
				wbuf.clear();
				Operation o=getCurrentWriteOp();
				while(o != null && toWrite < wbuf.capacity()) {
					assert o.getState() == Operation.State.WRITING;
					ByteBuffer obuf=o.getBuffer();
					int bytesToCopy=Math.min(wbuf.remaining(),
							obuf.remaining());
					byte b[]=new byte[bytesToCopy];
					obuf.get(b);
					wbuf.put(b);
					getLogger().debug("After copying stuff from %s: %s",
							o, wbuf);
					if(!o.getBuffer().hasRemaining()) {
						o.writeComplete();
						transitionWriteItem();

						preparePending();
						if(optimizeGets) {
							optimize();
						}

						o=getCurrentWriteOp();
					}
					toWrite += bytesToCopy;
				}
				wbuf.flip();
				assert toWrite <= wbuf.capacity()
					: "toWrite exceeded capacity: " + this;
				assert toWrite == wbuf.remaining()
					: "Expected " + toWrite + " remaining, got "
					+ wbuf.remaining();
			} else {
				getLogger().debug("Buffer is full, skipping");
			}
		}

		public void transitionWriteItem() {
			Operation op=removeCurrentWriteOp();
			assert op != null : "There is no write item to transition";
			assert op.getState() == Operation.State.READING;
			getLogger().debug("Transitioning %s to read", op);
			readQ.add(op);
		}

		public void optimize() {
			// make sure there are at least two get operations in a row before
			// attempting to optimize them.
			if(writeQ.peek() instanceof GetOperation) {
				getOp=(GetOperation)writeQ.remove();
				if(writeQ.peek() instanceof GetOperation) {
					OptimizedGet og=new OptimizedGet(getOp);
					getOp=og;

					while(writeQ.peek() instanceof GetOperation) {
						GetOperation o=(GetOperation) writeQ.remove();
						if(!o.isCancelled()) {
							og.addOperation(o);
						}
					}

					// Initialize the new mega get
					getOp.initialize();
					assert getOp.getState() == Operation.State.WRITING;
					getLogger().debug(
						"Set up %s with %s keys and %s callbacks",
						this, og.numKeys(), og.numCallbacks());
				}
			}
		}

		public Operation getCurrentReadOp() {
			return readQ.peek();
		}

		public Operation removeCurrentReadOp() {
			return readQ.remove();
		}

		public Operation getCurrentWriteOp() {
			return getOp == null ? writeQ.peek() : getOp;
		}

		public Operation removeCurrentWriteOp() {
			Operation rv=getOp;
			if(rv == null) {
				rv=writeQ.remove();
			} else {
				getOp=null;
			}
			return rv;
		}

		public boolean hasReadOp() {
			return !readQ.isEmpty();
		}

		public boolean hasWriteOp() {
			return !(getOp == null && writeQ.isEmpty());
		}

		public void addOp(Operation op) {
			boolean added=inputQueue.add(op);
			assert added; // documented to throw an IllegalStateException
		}

		public int getSelectionOps() {
			int rv=0;
			if(channel.isConnected()) {
				if(hasReadOp()) {
					rv |= SelectionKey.OP_READ;
				}
				if(toWrite > 0 || hasWriteOp()) {
					rv |= SelectionKey.OP_WRITE;
				}
			} else {
				rv = SelectionKey.OP_CONNECT;
			}
			return rv;
		}

		@Override
		public String toString() {
			int sops=0;
			if(sk!= null && sk.isValid()) {
				sops=sk.interestOps();
			}
			int rsize=readQ.size() + (getOp == null ? 0 : 1);
			int wsize=writeQ.size();
			int isize=inputQueue.size();
			return "{QA sa=" + socketAddress + ", #Rops=" + rsize
				+ ", #Wops=" + wsize
				+ ", #iq=" + isize
				+ ", topRop=" + getCurrentReadOp()
				+ ", topWop=" + getCurrentWriteOp()
				+ ", toWrite=" + toWrite
				+ ", interested=" + sops + "}";
		}
	}
}
