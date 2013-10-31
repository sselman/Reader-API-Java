/**
 * Sample program that reads tags in the background and prints the
 * tags found.
 */

// Import the API
package samples;
import com.thingmagic.*;

public class multireadasync
{
  public static void main(String argv[])
  {
    // Program setup
    /*if (argv.length != 1)
    {
      System.out.print("Please provide reader URL, such as:\n"
                       + "tmr:///com4\n"
                       + "tmr://my-reader.example.com\n");
      System.exit(1);
    }*/
    
    // Create Reader object, connecting to physical device
    try
    { 
      Reader r,r1;
      TagReadData[] tags;
      
      r = Reader.create(argv[0]);
      r1=Reader.create(argv[1]); 	    
      r.connect();
      r1.connect();
      if (Reader.Region.UNSPEC == (Reader.Region)r.paramGet("/reader/region/id"))
      {
          r.paramSet("/reader/region/id", Reader.Region.NA);
      }
      if (Reader.Region.UNSPEC == (Reader.Region)r1.paramGet("/reader/region/id"))
      {
          r1.paramSet("/reader/region/id", Reader.Region.NA);
      }
      // Create and add tag listener
      ReadListener rl = new PrintListener();
      r.addReadListener(rl);
	    
      ReadListener rl1 = new PrintListener();
      r1.addReadListener(rl1);

      // search for tags in the background
      r.startReading();
      r1.startReading();
      Thread.sleep(5000);
      r.stopReading();
      r1.stopReading();
      r.removeReadListener(rl);
      r1.removeReadListener(rl1);

      // Shut down reader
      r.destroy();
      r1.destroy();

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

  static class PrintListener implements ReadListener
  {
    public void tagRead(Reader r, TagReadData tr)
    {
      System.out.println("Background read: " + tr.toString());
    }

  }

}
