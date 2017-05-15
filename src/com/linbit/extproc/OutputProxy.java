package com.linbit.extproc;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingDeque;

public class OutputProxy implements Runnable
{
    public static interface Event // marker interface
    {
    }

    // Initial data buffer size 64 kiB
    public static final int INIT_DATA_SIZE = 0x10000;

    // Data buffer size increment 512 kiB
    public static final int DATA_SIZE_INC = 0x80000;

    // Maximum data size 4 MiB
    public static final int MAX_DATA_SIZE = 0x400000;

    public static final int EOF = -1;

    private final InputStream dataIn;
    private byte[] data;
    private int dataPos;
    private int dataLimit;

    private final BlockingDeque<Event> deque;
    private final byte delimiter;
    private final boolean useOut;

    private boolean shutdown;

    public OutputProxy(
        final InputStream in,
        final BlockingDeque<Event> deque,
        final byte delimiter,
        final boolean useOut
    )
    {
        dataIn = in;
        this.deque = deque;
        this.delimiter = delimiter;
        this.useOut = useOut;
        data = new byte[INIT_DATA_SIZE];
        dataPos = 0;
        dataLimit = 0;

        shutdown = false;
    }

    @Override
    public void run()
    {
        int read = 0;
        while (read != EOF)
        {
            // First read from the InputStream
            try
            {
                read = dataIn.read(data, dataPos, data.length - dataPos);

                if (read != EOF)
                {
                    dataLimit += read;
                    // Search for the delimiter starting from dataPos
                    while (dataPos < dataLimit)
                    {
                        if (data[dataPos] == delimiter)
                        {
                            // Put the found data into the deque
                            byte[] delimitedData = new byte[dataPos];
                            System.arraycopy(data, 0, delimitedData, 0, dataPos);
                            addToDeque(delimitedData);

                            // Skip the delimiter
                            dataPos += 2;

                            // Copy all remaining data to the start of our array
                            System.arraycopy(data, dataPos, data, 0, dataLimit - dataPos);
                            dataLimit -= dataPos;
                            dataPos = 0;
                        }
                        else
                        {
                            ++dataPos;
                        }
                    }

                    if (dataLimit == data.length)
                    {
                        if(dataLimit < MAX_DATA_SIZE)
                        {
                            byte[] enlarged = new byte[data.length + DATA_SIZE_INC];
                            System.arraycopy(data, 0, enlarged, 0, data.length);
                            data = enlarged;
                        }
                        else
                        {
                            addToDeque(new MaxBufferSizeReached());
                        }
                    }
                }
            }
            catch (IOException ioExc)
            {
                if (!shutdown)
                {
                    try
                    {
                        addToDeque(ioExc);
                    }
                    catch (InterruptedException exc)
                    {
                        // FIXME: Error reporting required
                        exc.printStackTrace();
                    }
                }
            }
            catch (InterruptedException interruptedExc)
            {
                if (!shutdown)
                {
                    // FIXME: Error reporting required
                    interruptedExc.printStackTrace();
                }
            }
        }
    }

    private void addToDeque(byte[] delimitedData) throws InterruptedException
    {
        Event event;
        if (useOut)
        {
            event = new StdOutEvent(delimitedData);
        }
        else
        {
            event = new StdErrEvent(delimitedData);
        }
        deque.put(event); // may block
    }

    private void addToDeque(Exception exc) throws InterruptedException
    {
        deque.put(new ExceptionEvent(exc));
    }

    public void expectShutdown()
    {
        shutdown = true;
    }

    public static class StdOutEvent implements Event
    {
        public byte[] data;

        public StdOutEvent()
        {
        }

        public StdOutEvent(byte[] data)
        {
            this.data = data;
        }
    }

    public static class StdErrEvent implements Event
    {
        public byte[] data;

        public StdErrEvent()
        {
        }

        public StdErrEvent(byte[] data)
        {
            this.data = data;
        }
    }

    public static class ExceptionEvent implements Event
    {
        public Exception exc;

        public ExceptionEvent()
        {
        }

        public ExceptionEvent(Exception exc)
        {
            this.exc = exc;
        }
    }

    public static class MaxBufferSizeReached extends Exception
    {
        private static final long serialVersionUID = 3479687941054839688L;
    }
}
