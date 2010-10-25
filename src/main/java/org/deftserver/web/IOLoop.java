package org.deftserver.web;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.deftserver.io.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public enum IOLoop {
	
	INSTANCE;
	
	private final Logger logger = LoggerFactory.getLogger(IOLoop.class);

	private static final long TIMEOUT = 250;	// 0.25s in ms
	private static final long TIMEOUT_CALLBACK_PERIOD = 2 * 1000;	//2s in ms

	private Selector selector;
	private long lastTimeout;
	
	private final Map<SelectableChannel, IOHandler> handlers = Maps.newHashMap();
	private final List<Timeout> timeouts = Lists.newLinkedList();
	private final Map<SelectableChannel, Timeout> keepAliveTimeouts = Maps.newHashMap();
	
	private IOLoop() {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			logger.error("Could not open selector: {}", e.getMessage());
		}
	}
	
	public void start() {
		Thread.currentThread().setName("I/O-LOOP");

		while (true) {
			try {
				if (selector.select(TIMEOUT) == 0) {
					invokeTimeouts();
					continue;
				}

				Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
				while (keys.hasNext()) {
					SelectionKey key = keys.next();
					IOHandler handler = handlers.get(key.channel());
					if (key.isAcceptable()) {
						handler.handleAccept(key);
					}
					if (key.isReadable()) {
						handler.handleRead(key);
					}
					if (key.isValid() && key.isWritable()) {
						handler.handleWrite(key);
					}
					keys.remove();
				}
				invokeTimeouts();

			} catch (IOException e) {
				logger.error("Exception received in IOLoop: {}", e);			
			}
		}
	}

	private void invokeTimeouts() {
		long now = System.currentTimeMillis();
		if (now >= lastTimeout + TIMEOUT_CALLBACK_PERIOD) {
			Iterator<Timeout> iter = timeouts.iterator();
			while (iter.hasNext()) {
				Timeout candidate = iter.next();
				if (candidate.getTimeout() <= now) {
					candidate.getCallback().onCallback();
					iter.remove();
					logger.debug("generic timeout triggered: {}", candidate);
				}
			}
		
			iter = keepAliveTimeouts.values().iterator();
			while (iter.hasNext()) {
				Timeout candidate = iter.next();
				if (candidate.getTimeout() <= now) {
					candidate.getCallback().onCallback();
					iter.remove();
					logger.debug("keep-alive timeout triggered: {}", candidate);
				}
			}

			lastTimeout = now;
		}
	}

	public SelectionKey addHandler(SelectableChannel channel, IOHandler handler, int interestOps, Object attachment) {
		handlers.put(channel, handler);
		return registerChannel(channel, interestOps, attachment);		
	}
	
	public void removeHandler(SelectableChannel channel) {
		handlers.remove(channel);
	}
	
	public void addTimeout(Timeout timeout) {
		logger.debug("added generic timeout: {}", timeout);
		timeouts.add(timeout);
	}
	
	public void addKeepAliveTimeout(SelectableChannel channel, Timeout timeout) {
		logger.debug("added keep-alive timeout: {}", timeout);
		keepAliveTimeouts.put(channel, timeout);
	}
	
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return keepAliveTimeouts.containsKey(channel);
	}
	
//	public <T> void addCallback(AsyncResult<T> cb) {
//		// TODO RS 101022 store this timeout 
//	}

	private SelectionKey registerChannel(SelectableChannel channel, int interestOps, Object attachment) {
		try {
			return channel.register(selector, interestOps, attachment);
		} catch (ClosedChannelException e) {
			removeHandler(channel);
			logger.error("Could not register channel: {}", e.getMessage());		
		}		
		return null;
	}

}
