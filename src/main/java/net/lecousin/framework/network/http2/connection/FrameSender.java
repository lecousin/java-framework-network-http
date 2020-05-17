package net.lecousin.framework.network.http2.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.Executable;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.util.Pair;

class FrameSender {

	private HTTP2Connection conn;
	int sendTimeout;
	private int frameProductionThreshold;
	private DataToSendProvider dataProvider = new DataToSendProvider();
	private LinkedList<Pair<ByteBuffer, Async<IOException>>> readyToSend = new LinkedList<>();
	private int readyToSendSize = 0;
	private Object frameProductionLock = new Object();
	private Task<Void, NoException> frameProductionTask = null;
	private Task<Void, NoException> nextFrameProductionTask = null;
	
	public FrameSender(HTTP2Connection conn, int sendTimeout, int frameProductionThreshold) {
		this.conn = conn;
		this.sendTimeout = sendTimeout;
		this.frameProductionThreshold = frameProductionThreshold;
	}
	
	void close(ClosedChannelException closed) {
		synchronized (readyToSend) {
			for (Pair<ByteBuffer, Async<IOException>> p : readyToSend)
				if (p.getValue2() != null)
					p.getValue2().error(closed);
			readyToSend.clear();
		}
	}
	
	void launchFrameProduction() {
		synchronized (frameProductionLock) {
			if (nextFrameProductionTask != null) return;
			nextFrameProductionTask = Task.cpu("Produce HTTP/2 frame", Task.Priority.RATHER_IMPORTANT, new FrameProducer());
		}
		if (frameProductionTask == null)
			nextFrameProductionTask.start();
		else
			nextFrameProductionTask.startAfter(frameProductionTask);
	}

	private class DataToSendProvider implements Supplier<List<ByteBuffer>> {
		@Override
		public List<ByteBuffer> get() {
			List<ByteBuffer> list = new LinkedList<>();
			List<Async<IOException>> sp = new LinkedList<>();
			synchronized (readyToSend) {
				for (Pair<ByteBuffer, Async<IOException>> p : readyToSend) {
					list.add(p.getValue1());
					if (p.getValue2() != null)
						sp.add(p.getValue2());
				}
				readyToSend.clear();
				readyToSendSize = 0;
			}
			Task.cpu("HTTP/2 frames sent", t -> {
				for (Async<IOException> done : sp)
					done.unblock();
				launchFrameProduction();
				return null;
			}).start();
			return list;
		}
	}

	private class FrameProducer implements Executable<Void, NoException> {
		@Override
		public Void execute(Task<Void, NoException> taskContext) {
			synchronized (frameProductionLock) {
				frameProductionTask = taskContext;
				nextFrameProductionTask = null;
			}
			while (!conn.isClosing()) {
				synchronized (readyToSend) {
					if (conn.logger.trace())
						conn.logger.trace("Waiting to be sent: " + readyToSendSize + "/" + frameProductionThreshold);
					if (readyToSendSize >= frameProductionThreshold)
						return null;
				}
				// produce frames
				LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list = conn.peekNextFramesToProduce();
				if (list.isEmpty())
					return null;
				produceFrames(list);
			}
			return null;
		}
		
		private void produceFrames(LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list) {
			LinkedList<Pair<ByteBuffer, Async<IOException>>> production = new LinkedList<>();
			int productionSize = 0;
			for (Pair<HTTP2Frame.Writer, Async<IOException>> frame : list) {
				HTTP2Frame.Writer producer = frame.getValue1();
				do {
					int maxSize = conn.getSendMaxFrameSize();
					ByteArray data = producer.produce(maxSize, conn.bufferCache);
					ByteBuffer b = data.toByteBuffer();
					production.add(new Pair<>(b, producer.canProduceMore() ? null : frame.getValue2()));
					productionSize += b.capacity();
					if (producer instanceof HTTP2Data) {
						int dataSize = data.remaining() - HTTP2FrameHeader.LENGTH;
						conn.decrementConnectionAndStreamSendWindowSize(producer.getStreamId(), dataSize);
					}
					if (conn.logger.trace()) {
						HTTP2FrameHeader header = new HTTP2FrameHeader();
						header.createConsumer().consume(data.toByteBuffer().asReadOnlyBuffer());
						conn.logger.trace("Frame ready to be sent: " + header);
					}
				} while (producer.canProduceMore() && readyToSendSize + productionSize < frameProductionThreshold);
			}
			send(production, productionSize);
		}
		
		private void send(LinkedList<Pair<ByteBuffer, Async<IOException>>> toSend, int toSendSize) {
			boolean first;
			synchronized (readyToSend) {
				first = readyToSend.isEmpty();
				readyToSend.addAll(toSend);
				readyToSendSize += toSendSize;
			}
			if (first) {
				Async<IOException> send = new Async<>();
				send.onError(e -> {
					conn.closing = true;
					if (e instanceof ClosedChannelException)
						return;
					conn.logger.error("Error sending frames on " + conn.remote, e);
					conn.remote.close();
				});
				conn.remote.newDataToSendWhenPossible(dataProvider, send, sendTimeout);		
			}
		}
	}

}
