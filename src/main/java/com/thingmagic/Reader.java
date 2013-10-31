/*
 * Copyright (c) 2008 ThingMagic, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.thingmagic;

import java.util.EnumMap;
import com.thingmagic.SerialReader.StatusReport;
import java.util.Vector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.InputStream;
import java.io.IOException;
import java.util.Collections;
import static com.thingmagic.TMConstants.*;

/**
 * The Reader class encapsulates a connection to a ThingMagic RFID
 * reader device and provides an interface to perform RFID operations
 * such as reading tags and writing tag IDs. Reads can be done on
 * demand, with the {@link #read} method, or continuously in the
 * background with the {@link #startReading} method. Background reads
 * notify a listener objects of tags that are read.
 *
 * Methods which communicate with the reader can throw ReaderException
 * if the communication breaks down. Other reasons for throwing
 * ReaderException are documented in the individual methods.
 *
 * Operations which take an argument for a tag to operate on may
 * optionally be passed a null argument. This lets the reader choose
 * what tag to use, but may not work if multiple tags are
 * present. This use is recommended only when exactly one tag is known
 * to be in range.
 */
abstract public class Reader
{

  final protected List<ReadListener> readListeners;
  final protected List<ReadExceptionListener> readExceptionListeners;
  protected List<StatusListener> statusListeners;
  boolean connected;
  boolean[] defaultAdded = {true,true};
  boolean[] customAdded = {false,false};
  Thread readerThread, notifierThread, exceptionNotifierThread;
  BackgroundReader backgroundReader;
  ContinuousReader continuousReader;
  BackgroundNotifier backgroundNotifier;
  ExceptionNotifier exceptionNotifier;
  final BlockingQueue<TagReadData> tagReadQueue;
  final BlockingQueue<ReaderException> exceptionQueue;
  Map<String,Setting> params;
  Map<StatusListener,StatusReport> statusMap;
  URI uri;
  boolean isTrueAsyncStopped=false;
  int transportTimeout = 5000;
  int commandTimeout = 1000;
  public static TransportListener simpleTransportListener;


  /**
   * RFID regulatory regions
   */
  public enum Region
  {
      /** Region not set */ UNSPEC,
      /** European Union */ EU, 
      /** India */ IN,
      /** Japan */ JP, 
      /** Korea */ KR, 
      /** Korea (revised) */ KR2, 
      /** North America */ NA, 
      /** China */ PRC,
      /** China(840MHZ) */ PRC2,
      /** European Union (revised) */ EU2, 
      /** European Union (revised) */ EU3,
      /** Australia */ AU,
      /** New Zealand !!EXPERIMENTAL!! */ NZ,
      /** No-limit region */ OPEN,
      /** Unrestricted access to full hardware range */ MANUFACTURING;
  }

  static final Map<Reader.Region, Integer> regionToCodeMap;
  static
  {
    regionToCodeMap = new EnumMap<Reader.Region, Integer>(Reader.Region.class);
    regionToCodeMap.put(Reader.Region.UNSPEC, 0);
    regionToCodeMap.put(Reader.Region.NA, 1);
    regionToCodeMap.put(Reader.Region.EU, 2);
    regionToCodeMap.put(Reader.Region.KR, 3);
    regionToCodeMap.put(Reader.Region.IN, 4);
    regionToCodeMap.put(Reader.Region.JP, 5);
    regionToCodeMap.put(Reader.Region.KR2, 9);
    regionToCodeMap.put(Reader.Region.PRC, 6);
    regionToCodeMap.put(Reader.Region.PRC2, 10);
    regionToCodeMap.put(Reader.Region.EU2, 7);
    regionToCodeMap.put(Reader.Region.EU3, 8);
    regionToCodeMap.put(Reader.Region.AU, 11);
    regionToCodeMap.put(Reader.Region.NZ, 12);
    regionToCodeMap.put(Reader.Region.OPEN, 255);
  }

  static final Map<Integer, Reader.Region> codeToRegionMap;
  static
  {
    codeToRegionMap = new HashMap<Integer, Reader.Region>();
    codeToRegionMap.put(0, Reader.Region.UNSPEC);
    codeToRegionMap.put(1, Reader.Region.NA);
    codeToRegionMap.put(2, Reader.Region.EU);
    codeToRegionMap.put(3, Reader.Region.KR);
    codeToRegionMap.put(4, Reader.Region.IN);
    codeToRegionMap.put(5, Reader.Region.JP);
    codeToRegionMap.put(9, Reader.Region.KR2);
    codeToRegionMap.put(6, Reader.Region.PRC);
    codeToRegionMap.put(7, Reader.Region.EU2);
    codeToRegionMap.put(8, Reader.Region.EU3);
    codeToRegionMap.put(11, Reader.Region.AU);
    codeToRegionMap.put(12, Reader.Region.NZ);
    codeToRegionMap.put(255, Reader.Region.OPEN);
    codeToRegionMap.put(10, Reader.Region.PRC2);
  }

  protected void checkConnection()
    throws ReaderException
  {
    if (connected == false)
    {
      throw new ReaderException("No reader connected to reader object");
    }
  }

  // Level 1 API
  // note no public constructor
  Reader()
  {
    readListeners = new Vector<ReadListener>();
    readExceptionListeners = new Vector<ReadExceptionListener>();
    statusListeners = new ArrayList<StatusListener>();
    statusListeners = Collections.synchronizedList(statusListeners);
    statusMap = new HashMap<StatusListener,StatusReport>();
    connected = false;
    
    tagReadQueue = new LinkedBlockingQueue<TagReadData>();
    exceptionQueue = new LinkedBlockingQueue<ReaderException>();

    initparams();
  }

  /**
   * Return an instance of a Reader class that is associated with a
   * RFID reader on a particular communication channel. The
   * communication channel is not established until the connect()
   * method is called. Note that some readers may need parameters
   * (such as the regulatory region) set before the connect will
   * succeed.
   *
   * @param uriString an identifier for the reader to connect to, with
   * a URI syntax. The scheme can be <tt>eapi</tt> for the embedded
   * module protocol, <tt>rql</tt> for the request query language, or
   * <tt>tmr</tt> to guess. The remainder of the URI identifies the
   * stream that the protocol will be spoken over, either a local host
   * serial port device or a TCP network port.
   *
   *  Examples include:<tt>
   *  <ul>
   *  <li>"eapi:///dev/ttyUSB0"
   *  <li>"eapi:///com1"
   *  <li>"rql://reader.example.com/"
   *  <li>"rql://proxy.example.com:2500/"
   *  <li>"tmr:///dev/ttyS0"
   *  <li>"tmr://192.168.1.101/"
   *  </ul></tt>
   *
   * @return the {@link Reader} object connected to the specified device
   * @throws ReaderException if reader initialization failed
   */
  public static Reader create(String uriString)
    throws ReaderException
  {    
    Reader reader = null;
    URI uri;

    try
    {
      uri = new URI(uriString);
    } 
    catch (URISyntaxException e)
    {
      throw new ReaderException(e.getMessage());
    }

    String scheme = uri.getScheme();
    String authority = uri.getAuthority();
    String path = uri.getPath();
    String host = uri.getHost();
    int port = uri.getPort();

    if (scheme == null)
    {
      throw new ReaderException("Blank URI scheme");
    }

    if (scheme.equals("tmr"))
    {
      if(!(uri.toString().contains("///")))
      {
        reader = new LLRPReader(uri.getHost(), (uri.getPort()==-1)?5084:uri.getPort());
        if(host!=null && isLLRPReader((LLRPReader)reader))
        {
            scheme = "llrp";
        }
        else if(host != null)
        {
            scheme = "rql";
        }
      }
      else
      {
        scheme = "eapi";
      }
    }

    if (scheme.equals("eapi"))
    {
      if (authority != null && !authority.equals(""))
      {
        throw new ReaderException("Remote hosts not supported for " + scheme);
      }

      reader = new SerialReader(path);
    }
    else if (scheme.equals("rql"))
    {
      if (!(path.equals("") || path.equals("/")))
      {
        throw new ReaderException("Path not supported for " + scheme);
      }

      if (port == -1)
      {
        reader = new RqlReader(host);
      }
      else
      {
        reader = new RqlReader(host, port);
      }
    }
    else if (scheme.equals("llrp"))
    {
        if(!_isConnected)
        {
            // llrp - low level reader protocol
            if (port == -1)
            {
                reader = new LLRPReader(host);
            }
            else
            {
                reader = new LLRPReader(host, port);
            }
        }
    }
    else
    {
      throw new ReaderException("Unknown URI scheme");
    }
    reader.uri = uri;


    return reader;
  }
  
  static boolean _isLLRP = true;
  static boolean _isConnected = false;

  protected void setReaderType(boolean isLLRP)
  {     
    _isLLRP = isLLRP;
  }
  protected static boolean isLLRPReader(LLRPReader llrpReader)
  {
      try
      {       
          llrpReader.llrpConnect();
          llrpReader.getReaderCapabilities(LLRPReader.ReaderConfigParams.SOFTWARE_VERSION);
          _isConnected = true;
          return true;
      } 
      catch (ReaderException ex)
      {
          if(!_isLLRP)
          {
              // GET READER CAPABILITIES MESSAGE fails saying UNSUPPORTED for non-llrp readers              
              llrpReader.destroy();
              return false;
          }                         
      }
      return false;
  }

  /**
   * Open the communication channel and initialize the session with
   * the reader.
   */
  public abstract void connect()
    throws ReaderException;

  /**
   * Shuts down the connection with the reader device.
   */
  public abstract void destroy();

  /**
   * Read RFID tags for a fixed duration.
   *
   * @param duration the time to spend reading tags, in milliseconds
   * @return the tags read
   * @see TagReadData
   */ 
  public abstract TagReadData[] read(long duration)
    throws ReaderException;

  /**
   * Read data from the memory bank of a tag. 
   *
   * @param target the tag to read from, or null
   * @param bank the tag memory bank to read from
   * @param address the byte address to start reading at
   * @param count the number of bytes to read
   * @return the bytes read
   *
   */
  public abstract byte[] readTagMemBytes(TagFilter target,
                                         int bank, int address, int count)
    throws ReaderException;


  /**
   * Read data from the memory bank of a tag. 
   *
   * @param target the tag to read from, or null
   * @param bank the tag memory bank to read from
   * @param address the word address to start reading from
   * @param count the number of words to read
   * @return the words read
   *
   */
  public abstract short[] readTagMemWords(TagFilter target,
                                          int bank, int address, int count)
    throws ReaderException;

  /**
   * Write data to the memory bank of a tag.
   *
   * @param target the tag to write to, or null
   * @param bank the tag memory bank to write to
   * @param address the byte address to start writing to
   * @param data the bytes to write
   *
   */
  public abstract void writeTagMemBytes(TagFilter target,
                                        int bank, int address, byte[] data)
    throws ReaderException;


  /**
   * Write data to the memory bank of a tag.
   *
   * @param target the tag to read from, or null
   * @param bank the tag memory bank to write to
   * @param address the word address to start writing to
   * @param data the words to write
   *
   */
  public abstract void writeTagMemWords(TagFilter target,
                                        int bank, int address, short[] data)
    throws ReaderException;

  /**
   * Write a new ID to a tag. 
   *
   * @param target the tag to write to, or null
   * @param newID the new tag ID to write
   */
  public abstract void writeTag(TagFilter target, TagData newID)
    throws ReaderException;

  /**
   * Perform a lock or unlock operation on a tag. The first tag seen
   * is operated on - the singulation parameter may be used to control
   * this. Note that a tag without an access password set may not
   * accept a lock operation or remain locked.
   *
   * @param target the tag to lock, or null
   * @param lock the locking action to take.
   */
  public abstract void lockTag(TagFilter target, TagLockAction lock)
    throws ReaderException;

  /**
   * Kill a tag. The first tag seen is killed.
   *
   * @param target the tag kill, or null
   * @param auth the authentication needed to kill the tag
   */
  public abstract void killTag(TagFilter target, TagAuthentication auth)
    throws ReaderException;
  /** 
   * Register a listener to be notified of asynchronous RFID read events.
   *
   * @param listener the ReadListener to add
   */
  public void addReadListener(ReadListener listener)
  {
      if (listener instanceof DefaultReadListener)
      {
            defaultAdded[0] = true;
      }
      else
      {
            customAdded[0] = true;
      }
      readListeners.add(listener);
  }

  /** 
   * Remove an listener from the list of listeners notified of asynchronous
   * RFID read events.
   *
   * @param listener the ReadListener to remove
   */
  public void removeReadListener(ReadListener listener)
  {
    if (listener instanceof DefaultReadListener)
      {
          defaultAdded[0] = false;
      }
      else
      {
          customAdded[0] = false;
      }
      readListeners.remove(listener);
  }


  /** 
   * Register a listener to be notified of asynchronous RFID read exceptions.
   *
   * @param listener the ReadExceptionListener to add
   */
    public void addReadExceptionListener(ReadExceptionListener listener) {
        if (listener instanceof DefaultReadExceptionListener) {
            defaultAdded[1] = true;
        } else {
            customAdded[1] = true;
        }
        readExceptionListeners.add(listener);
    }

  /** 
   * Remove a listener from the list of listeners notified of asynchronous
   * RFID read events.
   *
   * @param listener The ReadExceptionListener to remove
   */
    public void removeReadExceptionListener(ReadExceptionListener listener) {
        if (listener instanceof DefaultReadExceptionListener) {
            defaultAdded[1] = false;
        } else {
            customAdded[1] = false;
        }
        readExceptionListeners.remove(listener);
    }

    /**
   * Start reading RFID tags in the background. The tags found will be
   * passed to the registered read listeners, and any exceptions that
   * occur during reading will be passed to the registered exception
   * listeners. Reading will continue until stopReading() is called.
   *
   * @see #addReadListener
   * @see #addReadExceptionListener
   */
  public abstract void startReading();

   /**
   * Stop reading RFID tags in the background.
   */
  public abstract void stopReading();


  /**
   * Start reading RFID tags in the background. The tags found will be
   * passed to the registered read listeners, and any exceptions that
   * occur during reading will be passed to the registered exception
   * listeners. Reading will continue until stopReading() is called.
   *
   * @see #addReadListener
   * @see #addReadExceptionListener
   */
  protected synchronized void startReadingGivenRead(boolean isContinuous)
  {
    if(isContinuous)
    {        
        if (continuousReader == null)
        {     
            continuousReader = new ContinuousReader();
            readerThread = new Thread(continuousReader, "continuous reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
    }
    else
    {
        if (backgroundReader == null)
        {
            backgroundReader = new BackgroundReader();
            readerThread = new Thread(backgroundReader, "background reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
    }
    if (backgroundNotifier == null)
    {
      backgroundNotifier = new BackgroundNotifier();
      notifierThread = new Thread(backgroundNotifier, "background notifier");
      notifierThread.setDaemon(true);
      notifierThread.start();
    }
    if (exceptionNotifier == null)
    {
      exceptionNotifier = new ExceptionNotifier();
      exceptionNotifierThread = new Thread(exceptionNotifier, "exception notifier");
      exceptionNotifierThread.setDaemon(true);
      exceptionNotifierThread.start();
    }
    if(isContinuous)
    {
        continuousReader.readOn();
    }
    else
    {
        backgroundReader.readOn();
    }
  }

  /**
   * Stop reading RFID tags in the background.
   */
  protected void stopReadingGivenRead()
    throws InterruptedException
  {

      if (continuousReader != null) {
          continuousReader.readOff();
          continuousReader = null;
          readerThread.interrupt();
      }

      if (backgroundReader != null) {
          backgroundReader.readOff();
          backgroundReader = null;
          readerThread.interrupt();
      }
      if (backgroundNotifier != null) {
          backgroundNotifier.drainQueue();
          backgroundNotifier = null;
          notifierThread.interrupt();
      }
      if (exceptionNotifier != null) {
          exceptionNotifier.drainQueue();
          exceptionNotifier = null;
          exceptionNotifierThread.interrupt();
      }
  }

    void notifyReadListeners(TagReadData t) {

        synchronized (readListeners) {
            for (ReadListener rl : readListeners) {
                rl.tagRead(this, t);
            }
        }
    }

    void notifyExceptionListeners(ReaderException re) {
        synchronized (readExceptionListeners) {
            for (ReadExceptionListener rel : readExceptionListeners) {
                rel.tagReadException(this, re);
            }
        }
    }

    void notifyStatusListeners(StatusReport[] t) {
        synchronized (statusListeners) {
            for (StatusListener rl : statusListeners) {

                rl.statusMessage(this, t);
            }
        }
    }

  
  public static class GpioPin
  {
    // The class is immutable, so there's potential for caching the objects,
    // as per the "flyweight" pattern, if necessary.
    final public int id;
    final public boolean high;
    final public boolean output;

    public GpioPin(int id, boolean high)
    {
      this.id = id;
      this.high = high;
      this.output = false;
    }

    public GpioPin(int id, boolean high, boolean output)
    {
      this.id = id;
      this.high = high;
      this.output = output;
    }

    @Override public boolean equals(Object o)
    {
      if (!(o instanceof GpioPin))
      {
        return false;
      }
      GpioPin gp = (GpioPin)o;
      return (this.id == gp.id) && (this.high == gp.high) && (this.output == gp.output);
    }

    @Override
    public int hashCode()
    {
      int x;
      x = id * 2;
      if (high)
      {
        x++;
      }
      return x;
    }

    @Override
    public String toString()
    {
      return String.format("%2d%s%s", this.id, this.high?"H":"L", this.output?"O":"I");
    }

  }

  /**
   * Get the state of all of the reader's GPI pins.
   *
   * @return array of GpioPin objects representing the state of all input pins
   */
  public abstract GpioPin[] gpiGet()
    throws ReaderException;

  /**
   * Set the state of some GPO pins.
   *
   * @param state Array of GpioPin objects
   */
  public abstract void gpoSet(GpioPin[] state)
    throws ReaderException;

  /*
   * Quirk of Settings: if the type is modifiable (not primitive,
   * primitive-wrapper, or a String), then you have to be careful
   * about what is returned by paramGet, so that the caller can't
   * change the reader state by changing the returned object. As
   * there's no universal deep-copy in Java, we'll all just have to do
   * it ourselves in the get operation.
   */

  interface SettingAction
  {
    Object set(Object value)
      throws ReaderException;
    Object get(Object value)
      throws ReaderException;
  }

  abstract class ReadOnlyAction implements SettingAction
  {
    public Object set(Object value)
    {
      throw new UnsupportedOperationException();
    }
    public Object get(Object value)
      throws ReaderException
    {
      return value;
    }
  }

  class Setting
  {
    String originalName;
    Class type;
    Object value;
    SettingAction action;
    boolean writable;
    boolean confirmed;

    Setting(String name, Class t, Object def, boolean w, SettingAction act, boolean confirmed)
    {
      originalName = name;
      type = t;
      value = def;
      writable = w;
      
      action = act;
      this.confirmed = confirmed;
    }
  }


  void initparams()
  {
    Setting s;

    params = new HashMap<String,Setting>();

    addParam(TMR_PARAM_READ_ASYNCONTIME,
             Integer.class, 250, true,
             new SettingAction() {

            public Object set(Object value) throws ReaderException {
                if((Integer)value<0)
                {
                throw new IllegalArgumentException("negative value not permitted");
                }
                return value;
            }

            public Object get(Object value) throws ReaderException {
                return value;
            }
        }
             );
    addParam(TMR_PARAM_READ_ASYNCOFFTIME,
               Integer.class, 0, true,
               new SettingAction() {

            public Object set(Object value) throws ReaderException {
                if((Integer)value<0)
                {
                throw new IllegalArgumentException("negative value not permitted");
                }
                return value;
            }

            public Object get(Object value) throws ReaderException {
                return value;
            }
        });
        addParam(TMR_PARAM_GEN2_ACCESSPASSWORD,
             Gen2.Password.class, new Gen2.Password(0), true,
             new SettingAction()
             {
               public Object set(Object value)
               {
                 if (value == null)
                   value = new Gen2.Password(0);
                 else
                    value = ((Gen2.Password)value);//.value;
                 return value;
               }
               public Object get(Object value)
               {
                 return value;
               }
             });

      addParam(TMR_PARAM_READER_URI, String.class, null , true, 
              new ReadOnlyAction()
              {            
                public Object get(Object value) throws ReaderException 
                {
                    return uri.toString();
                }                
              });
      addParam(TMR_PARAM_TRANSPORTTIMEOUT,
              Integer.class, transportTimeout, true,
              new SettingAction() {

                  public Object set(Object value) throws ReaderException {
                      if ((Integer) value < 0 || (Integer) value > 65535) {
                          throw new IllegalArgumentException("Negative Transport timeout value not supported ");
                      }
                      transportTimeout = (Integer) value;
                      return transportTimeout;
                  }

                  public Object get(Object value) {
                      return transportTimeout;
                  }
              });
      addParam(TMR_PARAM_COMMANDTIMEOUT,
              Integer.class, commandTimeout, true,
              new SettingAction()
              {
                  public Object set(Object value)
                  {
                      if ((Integer) value < 0 || (Integer) value > 65535)
                      {
                          throw new IllegalArgumentException("Negative Command Timeout " + value);
                      }
                      commandTimeout = (Integer) value;
                      return value;
                  }

                  public Object get(Object value)
                  {
                      return commandTimeout;
                  }
              });
  }

  void addParam(String name, Class t, Object def, boolean w, SettingAction act)
  {
    if (def != null && t.isInstance(def) == false)
      throw new IllegalArgumentException("Wrong type for parameter initial value");
    Setting s = new Setting(name, t, def, w, act, true);
    params.put(name.toLowerCase(), s);
  }

  void addUnconfirmedParam(String name, Class t, Object def, boolean w, SettingAction act)
  {
    if (def != null && t.isInstance(def) == false)
      throw new IllegalArgumentException("Wrong type for parameter initial value");
    Setting s = new Setting(name, t, def, w, act, false);
    params.put(name.toLowerCase(), s);
  }

  /**
   * Get a list of the parameters available
   *
   * <p>Supported Parameters:
   * <ul>
   * <li> /reader/antenna/checkPort
   * <li> /reader/antenna/connectedPortList
   * <li> /reader/antenna/portList
   * <li> /reader/antenna/portSwitchGpos
   * <li> /reader/antenna/settlingTimeList
   * <li> /reader/antenna/txRxMap
   * <li> /reader/antennaMode
   * <li> /reader/baudRate
   * <li> /reader/commandTimeout
   * <li> /reader/currentTime
   * <li> /reader/description
   * <li> /reader/extendedEPC
   * <li> /reader/gen2/BLF
   * <li> /reader/gen2/accessPassword
   * <li> /reader/gen2/q
   * <li> /reader/gen2/session
   * <li> /reader/gen2/tagEncoding
   * <li> /reader/gen2/target
   * <li> /reader/gen2/tari
   * <li> /reader/gen2/writeEarlyExit
   * <li> /reader/gen2/writeMode
   * <li> /reader/gen2/writeReplyTimeout
   * <li> /reader/gpio/inputList
   * <li> /reader/gpio/outputList
   * <li> /reader/hostname
   * <li> /reader/iso180006b/BLF
   * <li> /reader/iso180006b/delimiter
   * <li> /reader/iso180006b/modulationDepth
   * <li> /reader/licenseKey
   * <li> /reader/powerMode
   * <li> /reader/radio/enablePowerSave
   * <li> /reader/radio/enableSJC
   * <li> /reader/radio/portReadPowerList
   * <li> /reader/radio/portWritePowerList
   * <li> /reader/radio/powerMax
   * <li> /reader/radio/powerMin
   * <li> /reader/radio/readPower
   * <li> /reader/radio/temperature
   * <li> /reader/radio/writePower
   * <li> /reader/read/asyncOffTime
   * <li> /reader/read/asyncOnTime
   * <li> /reader/read/plan
   * <li> /reader/region/hopTable
   * <li> /reader/region/hopTime
   * <li> /reader/region/id
   * <li> /reader/region/lbt/enable
   * <li> /reader/region/supportedRegions
   * <li> /reader/statistics
   * <li> /reader/status/antennaEnable
   * <li> /reader/status/frequencyEnable
   * <li> /reader/status/temperatureEnable
   * <li> /reader/tagReadData/enableReadFilter
   * <li> /reader/tagReadData/readFilterTimeout
   * <li> /reader/tagReadData/recordHighestRssi
   * <li> /reader/tagReadData/reportRssiInDbm
   * <li> /reader/tagReadData/tagopFailures
   * <li> /reader/tagReadData/tagopSuccesses
   * <li> /reader/tagReadData/uniqueByAntenna
   * <li> /reader/tagReadData/uniqueByData
   * <li> /reader/tagReadData/uniqueByProtocol
   * <li> /reader/tagop/antenna
   * <li> /reader/tagop/protocol
   * <li> /reader/transportTimeout
   * <li> /reader/uri
   * <li> /reader/userConfig
   * <li> /reader/userMode
   * <li> /reader/version/hardware
   * <li> /reader/version/model
   * <li> /reader/version/productGroup
   * <li> /reader/version/productGroupID
   * <li> /reader/version/serial
   * <li> /reader/version/software
   * <li> /reader/version/supportedProtocols
   * </ul>
   *
   * @return an array of the parameter names
   */
  public String[] paramList()
  {
    Vector<String> nameVec = new Vector<String>();
    int i = 0;
    // Create a new container so that removing entries isn't a problem
    for (Setting s : new ArrayList<Setting>(params.values()))
    {
      if (s.confirmed == false)
      {
        if (probeSetting(s) == false)
        {
          continue;
        }
      }
      nameVec.add(s.originalName);
    }
    return nameVec.toArray(new String[nameVec.size()]);
  }

  boolean probeSetting(Setting s)
  {
    try
    {
      s.action.get(null);
      s.confirmed = true;
    }
    catch (ReaderException e)
    {
    }

    if (s.confirmed == false)
    {
      params.remove(s.originalName);
    }
      
    return s.confirmed;
  }

  /** execute a TagOp */
   public abstract Object executeTagOp(TagOp tagOP,TagFilter target) throws ReaderException;



  /**
   * Get the value of a Reader parameter.
   *
   * @param key the parameter name
   * @return the value of the parameter, as an Object
   * @throws IllegalArgumentException if the parameter does not exist
   */
  public Object paramGet(String key)
    throws ReaderException
  {
    Setting s;

    s = params.get(key.toLowerCase());
    if (s == null)
    {
      throw new IllegalArgumentException("No parameter named '" + key + "'.");
    }

    // Maybe mention here that the parameter doesn't work here rather than
    // that it doesn't exist?
    if (s.confirmed == false && probeSetting(s) == false)
    {
      throw new IllegalArgumentException("No parameter named '" + key + "'.");
    }

    if (s.action != null)
    {
      s.value = s.action.get(s.value);
    }
    return s.value;
  }

  /**
   * Set the value of a Reader parameter.
   *
   * @param key the parameter name
   * @param value value of the parameter, as an Object
   * @throws IllegalArgumentException if the parameter does not exist,
   * is read-only, or if the Object is the wrong type for the
   * parameter.
   */
  public void paramSet(String key, Object value)
    throws ReaderException
  {
    Setting s;

    s = params.get(key.toLowerCase());
    if (s == null)
    {
      throw new IllegalArgumentException("No parameter named '" + key + "'.");
    }
    // Maybe mention here that the parameter doesn't work here rather than
    // that it doesn't exist?
    if (s.confirmed == false && probeSetting(s) == false)
    {
      throw new IllegalArgumentException("No parameter named '" + key + "'.");
    }
    if (s.writable == false)
    {
      throw new IllegalArgumentException("Parameter '" + key + "' is read-only.");
    }
    if (value != null && !s.type.isInstance(value))
    {
      throw new IllegalArgumentException("Wrong type " + value.getClass().getName() + 
                                         " for parameter '" + key + "'.");
    }
    if (s.action != null)
    {
      value = s.action.set(value);
    }
    s.value = value;
  }

  /**
   * Load a new firmware image into the device's nonvolatile memory.
   * This installs the given image data onto the device and restarts
   * it with that image. The firmware must be of an appropriate type
   * for the device. Interrupting this operation may damage the
   * reader.
   *
   * @param firmware a data stream of the firmware contents
   */
  public abstract void firmwareLoad(InputStream firmware)
    throws ReaderException, IOException;

  /**
   * Load a new firmware image into the device's nonvolatile memory.
   * This installs the given image data onto the device and restarts
   * it with that image. The firmware must be of an appropriate type
   * for the device. Interrupting this operation may damage the
   * reader.
   *
   * @param firmware a data stream of the firmware contents
   */
  public abstract void firmwareLoad(InputStream firmware,FirmwareLoadOptions loadOptions)
    throws ReaderException, IOException;

    /**
     * Default Read Exception handling mechanism.
     * This mechanism is useful to provide default implementation
     * for the tag read exceptions
     * DefaultReadExceptionListener is  a singleton implementation
     */
    public static class DefaultReadExceptionListener implements ReadExceptionListener {

        /**
         * private constructor to block external instantiation
         */
        private DefaultReadExceptionListener() {
            //creating instance
        }

        public void tagReadException(Reader r, ReaderException re) {
            System.out.println("tagReadException : " + re.getMessage());            
        }
    }

    private static DefaultReadExceptionListener defaultReadExceptionListener;

    /**
     * Retrieve the only instance of DefaultReadExceptionListener
     * using Lazy initialization
     * @return instance of DefaultReadExceptionListener
     */
    public synchronized static DefaultReadExceptionListener getDefaultReadExceptionListener() {

        if (null == defaultReadExceptionListener) {
            defaultReadExceptionListener = new DefaultReadExceptionListener();
        }
        return defaultReadExceptionListener;

    }

    /**
     * Default Read handling mechanism.
     * This mechanism is useful to provide default implementation
     * for the tag read exceptions
     * DefaultReadListener is  a singleton implementation
     */
    public static class DefaultReadListener implements ReadListener {

        /**
         * private constructor to block external instantiation
         */
        private DefaultReadListener() {
            //creating instance
        }       

        @Override
        public void tagRead(Reader r, TagReadData t)
        {
            try
            {
                System.out.println(r.paramGet(TMR_PARAM_READER_URI) + "|" + t.getAntenna() + "|" + t.getTag().getProtocol() + "|" + t.epcString());
            }
            catch (ReaderException ex)
            {
                ex.printStackTrace();
            }
        }
    }

    private static DefaultReadListener defaultReadListener;

    /**
     * Retrieve the only instance of DefaultReadListener
     * using Lazy initialization
     * @return instance of DefaultReadListener
     */
    public synchronized static DefaultReadListener getDefaultReadListener() {

        if (null == defaultReadListener) {
            defaultReadListener = new DefaultReadListener();
        }
        return defaultReadListener;
    }
    
    class BackgroundNotifier implements Runnable {

    public void run()
    {
      TagReadData t;
      try
      {
        while (true) {
          synchronized (tagReadQueue)
          {
            if (tagReadQueue.isEmpty())
            {
              tagReadQueue.notifyAll();
            }
          }
          t = tagReadQueue.take();
          notifyReadListeners(t);
        }
      }
      catch(InterruptedException ex)
      {
        // stopReading uses interrupt() to kill this thread;
        // users should not see that exception
      }
    }

    void drainQueue()
      throws InterruptedException
    {
      synchronized (tagReadQueue)
      {
        while (tagReadQueue.isEmpty() == false)
        {
          tagReadQueue.wait();
        }
      }
    }
  }

  class ExceptionNotifier implements Runnable
  {
    public void run()
    {
      try
      {
        while (true) {
          synchronized (exceptionQueue)
          {
            if (exceptionQueue.isEmpty())
            {
              exceptionQueue.notifyAll();
            }
          }
          ReaderException re = exceptionQueue.take();
          notifyExceptionListeners(re);
        }
      }
      catch(InterruptedException ex)
      {
        // stopReading uses interrupt() to kill this thread;
        // users should not see that exception
      }
    }

    void drainQueue()
      throws InterruptedException
    {
      synchronized (exceptionQueue)
      {
        while (exceptionQueue.isEmpty() == false)
        {
          exceptionQueue.wait();
        }
      }
    }
  }

  class BackgroundReader implements Runnable
  {
    boolean enabled, running;
    
    public void run()
    {
      TagReadData[] tags;
      int readTime, sleepTime;
      
      //Adding only default listeners, incase no customizations from the user are requested
      if(!customAdded[0] && defaultAdded[0])
      {
          addReadListener(Reader.getDefaultReadListener());
      }
      if(!customAdded[1] && defaultAdded[1])
      {
          addReadExceptionListener(Reader.getDefaultReadExceptionListener());
      }
      
      try
      {
        while (true)
        {
          synchronized (this)
          {
            running = false;
            this.notifyAll();  // Notify change in running
            while (enabled == false)
            {
              this.wait();  // Wait for enabled to change
            }
            running = true;
            this.notifyAll();  // Notify change in running
          }
          try
          {
            readTime = (Integer)paramGet(TMR_PARAM_READ_ASYNCONTIME);
            sleepTime = (Integer)paramGet(TMR_PARAM_READ_ASYNCOFFTIME);
            tags = read(readTime);
            for (TagReadData t : tags)
            {
              tagReadQueue.put(t);
            }
            if (sleepTime > 0)
            {
              Thread.sleep(sleepTime);
            }
          } 
          catch (ReaderException re)
          {
            exceptionQueue.put(re);
            enabled = false;
            running = false;
          }
        }
      } 
      catch (InterruptedException ie)
      {
        Thread.currentThread().interrupt();
        running = false;
        enabled = false;
      }
    }

    synchronized void readOn()
    {
      enabled = true;
      this.notifyAll();  // Notify change in enabled
    }

    synchronized void readOff()
    {
      enabled = false;
      try
      {
        while (running == true)
        {
          this.wait();  // Wait for running to change
        }
      }
      catch (InterruptedException ie)
      {
        Thread.currentThread().interrupt();
      }
    }

  }


  class ContinuousReader implements Runnable
  {
    public boolean enabled;
    boolean running, trueReading = true;
    boolean _continuousReading = false;

    ContinuousReader()
    {
        _continuousReading = true;
    }

    public void run()
    {      
      int readTime, sleepTime;

      //Adding only default listeners, incase no customizations from the user are requested
      if(!customAdded[0] && defaultAdded[0])
      {
          addReadListener(Reader.getDefaultReadListener());
      }
      if(!customAdded[1] && defaultAdded[1])
      {
          addReadExceptionListener(Reader.getDefaultReadExceptionListener());
          defaultAdded[1] = false;
      }

      try
      {
          while (!isTrueAsyncStopped) // true async is not stopped
          {
              synchronized (this)
              {
                  running = false;
                  this.notifyAll();  // Notify change in running
                  while (enabled == false)
                  {
                      this.wait();  // Wait for enabled to change
                  }
                  running = true;
                  this.notifyAll();  // Notify change in running
              }
              try
              {
                  readTime = (Integer) paramGet(TMR_PARAM_READ_ASYNCONTIME);
                  sleepTime = (Integer) paramGet(TMR_PARAM_READ_ASYNCOFFTIME);

                  if (trueReading)
                  {
                      // 2f (true continuous reading) command is sent only once
                      read(readTime);
                      trueReading = false;
                      if (sleepTime > 0)
                      {
                        Thread.sleep(sleepTime);
                      }
                  }                  
              }
              catch (ReaderException re)
              {                  
                  if(re instanceof ReaderCodeException && ((ReaderCodeException) re).code == EmbeddedReaderMessage.FAULT_TAG_ID_BUFFER_FULL)
                  {
                    trueReading = true;
                  }
                  else if(re instanceof ReaderCommException)
                  {                   
                    if(re.getMessage().equalsIgnoreCase("Timeout") || re.getMessage().equalsIgnoreCase("Invalid argument"))
                    {
                        notifyExceptionListeners(re);
                        stopReadingGivenRead();                        
                    }
                  }
                  else if(re.getMessage().contains("No Antenna"))
                  {
                    // do nothing, just notify listeners
                  }
                  else
                  {
                    running = false;
                    enabled = false;                    
                  }
                  exceptionQueue.put(re);
              }
          }//end of while                          
      }      
      catch (InterruptedException ie)
      {
        Thread.currentThread().interrupt();
        running = false;
        enabled = false;
      }
    }

    synchronized void readOn()
    {
      enabled = true;
      this.notifyAll();  // Notify change in enabled
    }

    synchronized void readOff()
    {     
      try
      {
        if(!running && _continuousReading)
        {
            this.wait();
        }
        enabled = false;
      
        while (running == true)
        {
          this.wait();  // Wait for running to change
        }
      }
      catch (InterruptedException ie)
      {
        Thread.currentThread().interrupt();
      }
    }

  }


  /**     
   * Register a listener to be notified of message packets.
   *
   * @param listener the TransportListener to add
   */
  public abstract void addTransportListener(TransportListener listener);

  /**     
   * Remove a listener from the list of listeners to be notified of
   * message packets.
   *
   * @param listener the TransportListener to add
   */
  public abstract void removeTransportListener(TransportListener listener);

  /**
   * Register a listener to be notified about the read statistics
   * @param listener - StatusLisenter to add
   */
  public abstract void addStatusListener(StatusListener listener);

  /**
   * remove a listener from the list of listeners to be notified of read statistics
   * @param listener - StatusListener to remove
   */
  public abstract void removeStatusListener(StatusListener listener);  
}
