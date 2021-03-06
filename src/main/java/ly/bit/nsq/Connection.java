package ly.bit.nsq;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ly.bit.nsq.exceptions.NSQException;
import ly.bit.nsq.util.ConnectionUtils;
import ly.bit.nsq.util.FrameType;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author dan
 *         <p/>
 *         This class (which we may want to make abstract later or something) should manage
 *         the connection to an instance of nsqd. It should have methods to send commands to nsqd,
 *         which I guess will get into the Netty stuff that you guys were talking about, as well as
 *         I guess some sort of callback function on data received - is that how Netty works?
 *         <p/>
 *         Anyway, I'm going to stub out what I think it should do when it receives a new message.
 *         We can all revisit as we flesh more stuff out.
 */
public abstract class Connection {
    private static final Logger log = Logger.getLogger(Connection.class.getName());

    protected NSQReader reader;
    protected String host;
    protected int port;
    protected AtomicInteger readyCount = new AtomicInteger();
    protected int maxInFlight; // TODO maybe replace this with something from reader, or else just set it from there
    protected AtomicBoolean closed = new AtomicBoolean(false);


    public void messageReceivedCallback(Message message) {
        int curReady = this.readyCount.decrementAndGet();
        if (curReady < Math.max(2, 0.25 * (float) this.maxInFlight)) {
            // should send ready now
            try {
                this.send(ConnectionUtils.ready(maxInFlight));
            } catch (NSQException e) {
                // broken conn
                this.close();

                log.log(Level.WARNING, e.getMessage(), e);
                return;
            }
            this.readyCount.set(maxInFlight);
        }
        this.reader.addMessageForProcessing(message);
    }

    public abstract void init(String host, int port, NSQReader reader);

    public abstract void send(String command, byte[]... datas) throws NSQException;

    public abstract void connect() throws NSQException;

    public abstract void readForever() throws NSQException;

    public abstract void close();

    public Message decodeMesage(byte[] data) throws NSQException {
        DataInputStream ds = new DataInputStream(new ByteArrayInputStream(data));
        try {
            long timestamp = ds.readLong(); // 8 bytes
            short attempts = ds.readShort(); // 2 bytes
            byte[] id = new byte[16];
            ds.read(id);
            byte[] body = new byte[data.length - 26];
            ds.read(body);
            return new Message(id, body, timestamp, attempts, this);
        } catch (IOException e) {
            throw new NSQException(e);
        }
    }

    public void handleResponse(byte[] response) throws NSQException {
        DataInputStream ds = new DataInputStream(new ByteArrayInputStream(response));
        try {
            FrameType ft = FrameType.fromInt(ds.readInt());
            switch (ft) {
                case FRAMETYPERESPONSE:
                    if ("_heartbeat_".equals(new String(response).trim())) {
                        this.send(ConnectionUtils.nop());
                    }

                    break;
                case FRAMETYPEMESSAGE:
                    byte[] messageBytes = Arrays.copyOfRange(response, 4, response.length);
                    Message msg = this.decodeMesage(messageBytes);
                    this.messageReceivedCallback(msg);
                    break;
                case FRAMETYPEERROR:
                    String errMsg = new String(Arrays.copyOfRange(response, 4, response.length));
                    throw new NSQException(errMsg);
            }
        } catch (IOException e) {
            // this isn't a *real* IOException, as we are only reading from a byte array.
            // if this were to be triggered, it would mean that there was a malformed message
            throw new NSQException(e);
        }
    }

    public String toString() {
        return this.host + ":" + this.port;
    }
}