package com.manaldush.telnet.protocol;

import com.manaldush.telnet.Command;
import com.manaldush.telnet.ICommandProcessor;
import com.manaldush.telnet.IClientSession;
import com.manaldush.telnet.exceptions.AbortOutputProcessException;
import com.manaldush.telnet.exceptions.GeneralTelnetException;
import com.manaldush.telnet.exceptions.InterruptProcessException;
import com.manaldush.telnet.exceptions.OperationException;
import com.manaldush.telnet.options.DefaultOption;
import com.manaldush.telnet.options.Option;
import com.manaldush.telnet.options.OptionState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.manaldush.telnet.protocol.Constants.CRLF;

/**
 * Implementation of IClientSession interface.
 * Created by Maxim.Melnikov on 22.06.2017.
 */
final class ImplTelnetClientSession implements IClientSession {
    private static final Charset DEFAULT_CHARSET = Charset.forName("ASCII");
    private ByteBuffer buffer = null;
    private final List<ICommandProcessor> tasks = new ArrayList<>();
    private final SocketChannel channel;
    private final ImplController controller;
    private final int initBufferSize;
    private final SelectionKey key;
    private ICommandProcessor currentTask = null;
    private Thread currentThread;
    private TaskExecutor executor;
    private boolean stop = false;
    private IDecoder decoder;
    private final String prompt;
    private final Map<Integer, Option> options;

    ImplTelnetClientSession(final SocketChannel _channel, final ImplController _controller, final int _initBufferSize,
                            SelectionKey _key, String _prompt) {
        channel = _channel;
        controller = _controller;
        initBufferSize = _initBufferSize;
        decoder = new Decoder(this);
        key = _key;
        prompt = _prompt;
        options = new HashMap<>();
        initOptions();
    }

    /**
     * Add buffer to current read buffer of session.
     * @param _b - byte
     */
    @Override
    public void addBuffer(byte _b) {
        if (buffer == null) buffer = ByteBuffer.allocate(initBufferSize);
        if (buffer.remaining() > 0) {
            buffer.put(_b);
        } else {
            buffer.flip();
            ByteBuffer nbuffer = ByteBuffer.allocate(buffer.capacity() + initBufferSize);
            nbuffer.put(buffer);
            nbuffer.put(_b);
            buffer = nbuffer;
        }
    }

    /**
     * Get buffer object.
     * @return buffer
     */
    @Override
    public ByteBuffer getBuffer() {
        //return copy
        if (buffer == null) return null;
        ByteBuffer nbuffer = ByteBuffer.allocate(buffer.capacity());
        nbuffer.put(buffer.array());
        nbuffer.position(buffer.position());
        return nbuffer;
    }

    /**
     * Decode byte buffer, that was read from connection.
     * @param _buffer - byte buffer
     * @param _bytesNum - number of readed bytes
     * @return list of read strings
     * @throws GeneralTelnetException - any telnet protocol error during processing
     * @throws IOException - IO errors
     */
    @Override
    public List<String> decode(final ByteBuffer _buffer, final int _bytesNum) throws GeneralTelnetException, IOException {
        synchronized (this) {
            if (stop) return null;
        }
        return decoder.decode(_buffer, _bytesNum);
    }

    /**
     * Reset buffer.
     */
    @Override
    public void resetBuffer() {
        buffer = null;
    }

    /**
     * Erase last character from buffer.
     */
    @Override
    public void eraseCharacter() {
        if (buffer == null) return;
        if (buffer.position() == 0) return;
        buffer.put(buffer.position(),(byte)0);
        buffer.position(buffer.position()-1);
    }

    /**
     * Create task from command and add it in queue for processing.
     * @param _cmd - command
     */
    @Override
    public void addTask(Command _cmd) {
        ICommandProcessor task =
                _cmd.getTemplate().getCommandProcessorFactory().build(_cmd, this);
        boolean thrStart = false;
        synchronized (this) {
            if (stop) return;
            tasks.add(task);
            if (currentThread == null) {
                currentThread = new Thread(new TaskExecutor());
                thrStart = true;
            }
        }
        if (thrStart) currentThread.start();
    }

    /**
     * Abort output of current executed task.
     * @throws AbortOutputProcessException - if some error occured during processing output abort in current executed task
     */
    @Override
    public synchronized void abortCurrentTask() throws AbortOutputProcessException {
        if (stop) return;
        if (currentTask == null) return;
        currentTask.abortOutput();
    }

    /**
     * Interrupt current executed task.
     * @throws InterruptProcessException - if some error occured during processing interruption current executed task
     */
    @Override
    public synchronized void interruptCurrentTask() throws InterruptProcessException {
        if (stop) return;
        if (currentTask == null) return;
        currentTask.interruptProcess();
    }

    /**
     * Write message in connection.
     * @param _msg - message
     * @throws IOException - if IO problem occured
     */
    @Override
    public void write(String _msg) throws IOException {
        byte[] b = str2Bytes(_msg);
        ByteBuffer buffer = ByteBuffer.allocate(b.length);
        buffer.put(b);
        buffer.flip();
        innerWrite(buffer);
    }

    /**
     * Write bytes in connection.
     * @param _b - bytes
     * @throws IOException - if IO problem occured
     */
    @Override
    public void write(byte[] _b) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(_b.length);
        buffer.put(_b);
        buffer.flip();
        innerWrite(buffer);
    }

    private void innerWrite(ByteBuffer buffer) throws IOException {
        try {
            channel.write(buffer);
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    private byte[] str2Bytes(String _msg) {
        return _msg.getBytes(DEFAULT_CHARSET);
    }

    /**
     * Command for close session.
     */
    @Override
    public synchronized void close() {
        innerClose();
    }

    /**
     * Return state of option on client.
     *
     * @param _val - option type
     * @return - option state
     */
    @Override
    public OptionState getOptionClientState(byte _val) {
        return options.get(_val & 0xFF).getClientState();
    }

    /**
     * Return state of option on server.
     *
     * @param _val - option type
     * @return - option state
     */
    @Override
    public OptionState getOptionServerState(byte _val) {
        return options.get(_val & 0xFF).getServerState();
    }

    /**
     * Set option state on a client.
     *
     * @param _val   - option value
     * @param _state - state of option
     */
    @Override
    public void setOptionClientState(byte _val, OptionState _state) {
        options.get(_val & 0xFF).setClientState(_state);
    }

    /**
     * Set option state on a server.
     *
     * @param _val   - option value
     * @param _state - state of option
     */
    @Override
    public void setOptionServerState(byte _val, OptionState _state) {
        options.get(_val & 0xFF).setServerState(_state);
    }

    /**
     * Sub negotiation process.
     *
     * @param _val     - value of option
     * @param _b       - sub negotiation bytes between option value and SE command
     * @param _charset
     */
    @Override
    public void subNegotiation(byte _val, List<Byte> _b, Charset _charset) {
        options.get(_val & 0xFF).setSubnegotiation(_b, this, _charset);
    }

    private void innerClose() {
        if (stop) return;
        key.cancel();
        if (executor == null) {
            this.resetSession();
        }
        this.stop = true;
    }

    private void resetSession() {
        try {
            controller.resetSession(channel);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class TaskExecutor implements Runnable {

        @Override
        public void run() {
            for(;;) {
                synchronized (ImplTelnetClientSession.this) {
                    currentTask = null;
                    if (ImplTelnetClientSession.this.stop){
                        executor = null;
                        ImplTelnetClientSession.this.resetSession();
                        currentThread = null;
                        return;
                    } else if (tasks.size() == 0) {
                        executor = null;
                        currentThread = null;
                        try {
                            ImplTelnetClientSession.this.write(CRLF);
                            ImplTelnetClientSession.this.write(Constants.GREEN);
                            ImplTelnetClientSession.this.write(str2Bytes(prompt));
                            ImplTelnetClientSession.this.write(Constants.RESET_COLOR);
                        } catch (IOException e) {
                            e.printStackTrace();
                            innerClose();
                        }
                        return;
                    }
                    currentTask = tasks.remove(0);
                }
                try {
                    currentTask.process();
                } catch (OperationException ex) {
                    ex.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                    close();
                }
            }
        }
    }

    private void initOptions() {
        for(byte b = Byte.MIN_VALUE; b < Byte.MAX_VALUE; b++) {
            int i = b & 0xFF;
            options.put(i, new DefaultOption(b));
        }
    }
}
