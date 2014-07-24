package com.nitorcreations.messages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import org.msgpack.MessagePack;
import org.msgpack.packer.Packer;
import org.msgpack.unpacker.BufferUnpacker;

public class MessageMapping {
	MessagePack msgpack = new MessagePack();

	public enum MessageType {
		PROC, CPU, MEM, DISK, OUTPUT, LOG, JMX, PROCESSCPU, ACCESS, LONGSTATS, HASH;
		public String lcName() {
			return toString().toLowerCase();
		}
	}

	private Map<MessageType, Class<? extends AbstractMessage>> messageTypes = new HashMap<MessageMapping.MessageType, Class<? extends AbstractMessage>>();
	private Map<Class<? extends AbstractMessage>, MessageType> messageClasses = new HashMap<Class<? extends AbstractMessage>, MessageType>();

	public MessageMapping() {
		messageTypes.put(MessageType.PROC, Processes.class);
		messageTypes.put(MessageType.CPU, CPU.class);
		messageTypes.put(MessageType.MEM, Memory.class);
		messageTypes.put(MessageType.DISK, DiskUsage.class);
		messageTypes.put(MessageType.OUTPUT, OutputMessage.class);
		messageTypes.put(MessageType.LOG, LogMessage.class);
		messageTypes.put(MessageType.JMX, JmxMessage.class);
		messageTypes.put(MessageType.PROCESSCPU, ProcessCPU.class);
		messageTypes.put(MessageType.ACCESS, AccessLogEntry.class);
		messageTypes.put(MessageType.LONGSTATS, LongStatisticsMessage.class);
		messageTypes.put(MessageType.HASH, HashMessage.class);
		

		for (java.util.Map.Entry<MessageType, Class<? extends AbstractMessage>> next : messageTypes.entrySet()) {
			messageClasses.put(next.getValue(), next.getKey());
		}
		registerTypes(msgpack);
	}

	public void registerTypes(MessagePack msgpack) {
		msgpack.register(Thread.State.class);
		msgpack.register(ThreadInfoMessage.class);
		msgpack.register(GcInfo.class);
		for (Class<?> next: messageTypes.values()) {
			msgpack.register(next );
		}
		msgpack.register(DeployerMessage.class);
	}

	public Class<? extends AbstractMessage> map(int type) {
		return messageTypes.get(MessageType.values()[type]);
	}
	public MessageType map(Class<?> msgclass) {
		return messageClasses.get(msgclass);
	}

	public ByteBuffer encode(List<AbstractMessage> msgs) throws IOException {
		LZ4Factory factory = LZ4Factory.fastestInstance();
		LZ4Compressor compressor = factory.fastCompressor();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Packer packer = msgpack.createPacker(out);
		for (AbstractMessage msg : msgs) {
          byte[] message = msgpack.write(msg);
		  MessageType type = map(msg.getClass()); 
		  packer.write(new DeployerMessage(type.ordinal(), message));
		}
		byte data[] = out.toByteArray();
		int maxCompressedLength = compressor.maxCompressedLength(data.length);
		byte[] compressed = new byte[maxCompressedLength];
		int compressedLength = compressor.compress(data, 0, data.length, compressed, 0, maxCompressedLength);
		if (compressedLength >= data.length) {
	        return ByteBuffer.allocate(4 + data.length).putInt(0).put(data);
		}
        return ByteBuffer.allocate(4 + compressedLength).putInt(compressedLength).put(compressed, 0, compressedLength);
	}

	public List<AbstractMessage> decode(byte[] data, int offset, int length) throws IOException {
		if (length < 4) return new ArrayList<AbstractMessage>();
		ByteBuffer read = ByteBuffer.wrap(data);
		int uclen = read.getInt();
		byte[] restored;
		if (uclen == 0) {
			restored = new byte[length - 4];
			read.get(restored);
		} else {
			restored = new byte[uclen];
			LZ4Factory factory = LZ4Factory.fastestInstance();
			LZ4FastDecompressor decompressor = factory.fastDecompressor();
			try {
				decompressor.decompress(data, offset + 4, restored , 0, uclen);
			} catch (Throwable e) {
				System.out.printf("Received buffer[%d], %d, %d - uncompressed len %d\n", data.length, offset, length, uclen);
				e.printStackTrace();
				return new ArrayList<AbstractMessage>();
			}
		}
		BufferUnpacker unpacker = msgpack.createBufferUnpacker(restored);
		ArrayList<AbstractMessage> ret = new ArrayList<AbstractMessage>();
		DeployerMessage next = unpacker.read(DeployerMessage.class);
		while (next != null) {
			ret.add(msgpack.read(next.message, map(next.type)));
			next = unpacker.read(DeployerMessage.class);
		}
	   return ret;
	}
}
