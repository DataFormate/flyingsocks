package com.lzf.flyingsocks.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ProxyResponseMessage extends ProxyMessage implements Message {

    private State state;

    public ProxyResponseMessage(String channelId) {
        super(channelId);
    }

    public ProxyResponseMessage(ByteBuf serialBuf) throws SerializationException {
        super(serialBuf);
    }

    public enum State {
        SUCCESS(0x00), FAILURE(0x01);

        private final byte head;

        State(int head) {
            this.head = (byte)head;
        }

        private static State getStateByHead(byte head) {
            for(State state : State.values())
                if(state.head == head)
                    return state;
            return null;
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public void setMessage(ByteBuf buf) {
        super.message = buf;
    }

    @Override
    public ByteBuf serialize() throws SerializationException {
        if(channelId == null || state == null)
            throw new SerializationException("ProxyResponseMessage is not complete, message detail: \n" + toString());

        byte[] cid = channelId.getBytes(CHANNEL_ENCODING);
        byte h = state.head;

        if(state == State.SUCCESS) {
            if(message == null)
                throw new SerializationException("When ProxyResponseMessage's state is SUCCESS, message must not be null");
            ByteBuf buf = Unpooled.buffer(2 + cid.length + 1 + 4 + message.readableBytes());
            buf.writeShort(cid.length);
            buf.writeBytes(cid);

            buf.writeByte(h);
            buf.writeInt(message.readableBytes());
            buf.writeBytes(getMessage());

            return buf;
        } else {
            ByteBuf buf;
            if(message == null) {
                buf = Unpooled.buffer(2 + cid.length + 1 + 4);
            } else {
                buf = Unpooled.buffer(2 + cid.length + 1 + 4 + message.readableBytes());
            }

            buf.writeShort(cid.length);
            buf.writeBytes(cid);

            buf.writeByte(h);
            if(message != null) {
                buf.writeInt(message.readableBytes());
                buf.writeBytes(getMessage());
            } else {
                buf.writeInt(0);
            }

            return buf;
        }
    }

    @Override
    public void deserialize(ByteBuf buf) throws SerializationException {
        short cidlen = buf.readShort();
        if(cidlen <= 0)
            throw new SerializationException("Illegal ProxyResponseMessage, client channel id length < 0");

        try {
            byte[] bid = new byte[cidlen];
            buf.readBytes(bid);
            String cid = new String(bid, CHANNEL_ENCODING);

            byte h = buf.readByte();
            State state = State.getStateByHead(h);

            ByteBuf msg;
            if(state == State.SUCCESS) {
                msg = Unpooled.buffer(buf.readInt());
                buf.readBytes(msg);
            } else if(state == State.FAILURE) {
                int len = buf.readInt();
                if(len > 0) {
                    msg = Unpooled.buffer(len);
                    buf.readBytes(msg);
                } else
                    msg = null;
            } else {
                throw new SerializationException("Unknown ProxyResponseMessage type " + h);
            }

            this.channelId = cid;
            this.message = msg;
            this.state = state;
        } catch (IndexOutOfBoundsException e) {
            throw new SerializationException("Illegal ProxyResponseMessage", e);
        }
    }

    @Override
    public String toString() {
        return "ProxyResponseMessage{" +
                "state=" + state +
                ", channelId='" + channelId + '\'' +
                ", message=" + message +
                '}';
    }
}
