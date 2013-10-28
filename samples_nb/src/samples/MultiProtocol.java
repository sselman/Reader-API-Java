/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package samples;
import com.thingmagic.*;
/**
 *
 * @author rsoni
 */
public class MultiProtocol
{
  static SerialPrinter serialPrinter;
  static StringPrinter stringPrinter;
  static TransportListener currentListener;

  static void usage()
  {
    System.out.printf("Usage: demo reader-uri <command> [args]\n" +
                      "  (URI: 'tmr:///COM1' or 'tmr://astra-2100d3/' " +
                      "or 'tmr:///dev/ttyS0')\n\n" +
                      "Available commands:\n");
    System.exit(1);
  }

   public static void setTrace(Reader r, String args[])
  {
    if (args[0].toLowerCase().equals("on"))
    {
        r.addTransportListener(Reader.simpleTransportListener);
        currentListener = Reader.simpleTransportListener;
    }
    else if (currentListener != null)
    {
        r.removeTransportListener(Reader.simpleTransportListener);
    }
  }

   static class SerialPrinter implements TransportListener
  {
    public void message(boolean tx, byte[] data, int timeout)
    {
      System.out.print(tx ? "Sending: " : "Received:");
      for (int i = 0; i < data.length; i++)
      {
        if (i > 0 && (i & 15) == 0)
          System.out.printf("\n         ");
        System.out.printf(" %02x", data[i]);
      }
      System.out.printf("\n");
    }
  }

  static class StringPrinter implements TransportListener
  {
    public void message(boolean tx, byte[] data, int timeout)
    {
      System.out.println((tx ? "Sending:\n" : "Receiving:\n") +
                         new String(data));
    }
  }
public static void main(String argv[]) throws ReaderException
  {
    TagFilter target;

    Reader r;
    int nextarg;
    boolean trace;

    r = null;
    target = null;
    trace = false;

    nextarg = 0;

    if (argv.length < 1)
      usage();

      if (argv[nextarg].equals("-v"))
      {
      trace = true;
      nextarg++;
      }
      r = Reader.create(argv[nextarg]);
      if (trace)
      {
        setTrace(r, new String[] {"on"});
      }
      r.connect();
      if (Reader.Region.UNSPEC == (Reader.Region)r.paramGet("/reader/region/id"))
      {
          r.paramSet("/reader/region/id", Reader.Region.NA);
      }
      try
      {
        ReadPlan rp[]=new ReadPlan[4];
        SimpleReadPlan plan1 = new SimpleReadPlan(null, TagProtocol.GEN2, null,null,0);
        SimpleReadPlan plan2 = new SimpleReadPlan(null, TagProtocol.ISO180006B, null,null,0);
        SimpleReadPlan plan3 = new SimpleReadPlan(null, TagProtocol.IPX256, null,null,0);
        SimpleReadPlan plan4 = new SimpleReadPlan(null, TagProtocol.IPX64, null,null,0);
        rp[0]=plan1;
        rp[1]=plan2;
        rp[2]=plan3;
        rp[3]=plan4;
        MultiReadPlan testMultiReadPlan = new MultiReadPlan(rp);
        r.paramSet("/reader/read/plan", testMultiReadPlan);
        TagReadData[] t;
        try
        {
            t = r.read(1000);
        }
        catch (ReaderException re)
        {
            System.out.printf("Error reading tags: %s\n", re.getMessage());
            return;
        }
        for(TagReadData trd : t)
        {
          // trd.getTag().getProtocol().toString();
            System.out.println(trd.getTag().getProtocol().toString()+": "+trd.toString());
        }

        r.destroy();
        }
        catch (ReaderException re)
        {
            System.out.println("ReaderException: " + re.getMessage());
        }
        catch (Exception re)
        {
            System.out.println("Exception: " + re.getMessage());
        }
      }
}
