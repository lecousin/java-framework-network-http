package net.lecousin.framework.network.http2.connection;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.collections.sort.RedBlackTreeInteger;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.network.http2.frame.HTTP2Data;
import net.lecousin.framework.network.http2.frame.HTTP2Frame;
import net.lecousin.framework.network.http2.frame.HTTP2FrameHeader;
import net.lecousin.framework.network.http2.frame.HTTP2WindowUpdate;
import net.lecousin.framework.util.Pair;

class StreamNode {

	HTTP2Stream stream;
	long sendWindowSize;
	long recvWindowSize;
	RedBlackTreeInteger<List<StreamNode>> dependentStreams = new RedBlackTreeInteger<>();
	private LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> waitingFrameProducers = new LinkedList<>();
	private boolean closeAfterLastProduction = false;

	StreamNode(HTTP2Stream stream, long sendWindowSize, long recvWindowSize) {
		this.stream = stream;
		this.sendWindowSize = sendWindowSize;
		this.recvWindowSize = recvWindowSize;
	}
	
	void forceClose(IOException error) {
		for (Pair<HTTP2Frame.Writer, Async<IOException>> p : waitingFrameProducers)
			if (p.getValue2() != null)
				p.getValue2().error(error);
		waitingFrameProducers.clear();
		LinkedList<StreamNode> children = new LinkedList<>();
		for (Iterator<List<StreamNode>> it = dependentStreams.orderedIterator(); it.hasNext(); )
			children.addAll(it.next());
		dependentStreams.clear();
		for (StreamNode child : children)
			child.forceClose(error);
	}
	
	boolean adjustSendWindowSize(long diff) {
		boolean mayProduce = sendWindowSize <= 0;
		sendWindowSize += diff;
		mayProduce &= sendWindowSize > 0;
		for (List<StreamNode> children : dependentStreams) {
			for (StreamNode child : children) {
				mayProduce |= child.adjustSendWindowSize(diff);
			}
		}
		return mayProduce;
	}
	
	// --- Frame producers ---
	
	void addProducer(HTTP2Frame.Writer producer, Async<IOException> ondone, boolean closeAfter) {
		if (producer.getType() == HTTP2FrameHeader.TYPE_WINDOW_UPDATE || producer.getType() == HTTP2FrameHeader.TYPE_PING)
			waitingFrameProducers.addFirst(new Pair<>(producer, ondone));
		else
			waitingFrameProducers.addLast(new Pair<>(producer, ondone));
		closeAfterLastProduction |= closeAfter;
	}
	
	void closeAfterLastProduction() {
		closeAfterLastProduction = true;
	}
	
	LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> removeNextFramesToProduce(boolean acceptData, List<HTTP2Stream> toClose) {
		LinkedList<Pair<HTTP2Frame.Writer, Async<IOException>>> list = new LinkedList<>();
		int deep = 0;
		do {
			boolean hasDeeper = removeFramesToProduce(list, acceptData, deep, toClose);
			if (!hasDeeper || !list.isEmpty())
				return list;
			deep++;
		} while (true);
	}
	
	private boolean removeFramesToProduce(
		List<Pair<HTTP2Frame.Writer, Async<IOException>>> frames, boolean acceptData, int deep, List<HTTP2Stream> toClose
	) {
		if (deep == 0)
			return removeFramesToProduceAtThisLevel(frames, acceptData, toClose);
		boolean hasDeeper = false;
		for (Iterator<List<StreamNode>> it = dependentStreams.orderedIterator(); it.hasNext(); ) {
			for (StreamNode child : it.next())
				hasDeeper |= child.removeFramesToProduce(frames, acceptData, deep - 1, toClose);
			if (!frames.isEmpty())
				return true;
		}
		return hasDeeper;
	}
	
	private boolean removeFramesToProduceAtThisLevel(
		List<Pair<HTTP2Frame.Writer, Async<IOException>>> frames, boolean acceptData, List<HTTP2Stream> toClose
	) {
		HTTP2WindowUpdate.Writer winUpdate = null;
		for (Iterator<Pair<HTTP2Frame.Writer, Async<IOException>>> it = waitingFrameProducers.iterator(); it.hasNext(); ) {
			Pair<HTTP2Frame.Writer, Async<IOException>> p = it.next();
			HTTP2Frame.Writer frame = p.getValue1();
			if (frame instanceof HTTP2WindowUpdate.Writer) {
				// merge window updates on same stream
				if (winUpdate == null)
					winUpdate = (HTTP2WindowUpdate.Writer)frame;
				else {
					HTTP2WindowUpdate.Writer update = (HTTP2WindowUpdate.Writer)frame;
					winUpdate.setIncrement(winUpdate.getIncrement() + update.getIncrement());
					it.remove();
					continue;
				}
			}
			if (!frame.canProduceMore()) {
				it.remove();
				continue;
			}
			if (frame instanceof HTTP2Data && (!acceptData || sendWindowSize <= 0))
				break;
			frames.add(p);
			if (frame.canProduceSeveralFrames())
				break;
			it.remove();
		}
		if (closeAfterLastProduction && waitingFrameProducers.isEmpty())
			toClose.add(stream);
		return !dependentStreams.isEmpty();
	}
	
	// --- Add a stream ---
	
	StreamNode createChild(HTTP2Stream stream, int weight, long sendWindowSize, long recvWindowSize) {
		StreamNode node = new StreamNode(stream, sendWindowSize, recvWindowSize);
		addChild(node, weight);
		return node;
	}
	
	void addChild(StreamNode child, int weight) {
		List<StreamNode> list = dependentStreams.get(weight);
		if (list != null) {
			list.add(child);
		} else {
			list = new LinkedList<>();
			list.add(child);
			dependentStreams.add(weight, list);
		}
	}

	
	// --- Remove a stream ---
	
	StreamNode remove(int id, boolean reassignDependentStreams) {
		for (Iterator<RedBlackTreeInteger.Node<List<StreamNode>>> it = dependentStreams.nodeIterator(); it.hasNext(); ) {
			RedBlackTreeInteger.Node<List<StreamNode>> nodes = it.next();
			for (Iterator<StreamNode> it2 = nodes.getElement().iterator(); it2.hasNext(); ) {
				StreamNode n = it2.next();
				if (n.stream.getStreamId() != id)
					continue;
				// found it
				it2.remove();
				if (nodes.getElement().isEmpty())
					dependentStreams.remove(nodes);
				if (!n.dependentStreams.isEmpty() && reassignDependentStreams)
					moveDependenciesToParent(n, nodes.getValue());
				return n;
			}
		}
		for (List<StreamNode> children : dependentStreams) {
			for (StreamNode child : children) {
				StreamNode removed = child.remove(id, reassignDependentStreams);
				if (removed != null)
					return removed;
			}
		}
		return null;
	}
	
	private void moveDependenciesToParent(StreamNode parent, int parentWeight) {
		/* When a stream is removed from the dependency tree, its dependencies
		   can be moved to become dependent on the parent of the closed stream.
		   The weights of new dependencies are recalculated by distributing the
		   weight of the dependency of the closed stream proportionally based on
		   the weights of its dependencies. */
		int total = 0;
		for (Iterator<RedBlackTreeInteger.Node<List<StreamNode>>> itChildren = parent.dependentStreams.nodeIterator();
			itChildren.hasNext(); ) {
			RedBlackTreeInteger.Node<List<StreamNode>> children = itChildren.next();
			total += children.getValue() * children.getElement().size();
		}
		if (total <= 0) total = 1;
		for (Iterator<RedBlackTreeInteger.Node<List<StreamNode>>> itChildren = parent.dependentStreams.nodeIterator();
			itChildren.hasNext(); ) {
			RedBlackTreeInteger.Node<List<StreamNode>> children = itChildren.next();
			int val = parentWeight * children.getValue() / total;
			List<StreamNode> list = dependentStreams.get(val);
			if (list == null) {
				list = new LinkedList<>();
				dependentStreams.add(val, list);
			}
			list.addAll(children.getElement());
		}
	}


}
