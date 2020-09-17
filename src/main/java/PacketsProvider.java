import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.Pcaps;

import java.util.Properties;

public class PacketsProvider {

    private static PacketsProvider instance = null;
    private static PcapHandle pcapHandle;
    private static String filePath;


    private PacketsProvider(final String filePath) {
        try {
            pcapHandle = Pcaps.openOffline(filePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static PacketsProvider getInstance() {
        if (instance == null)
            instance = new PacketsProvider(filePath);
        return instance;
    }

    public static void setFilePath(String pcapFilePath) {
        filePath = pcapFilePath;
    }

    public byte[] getNextRtpPacket() throws NotOpenException {
        return pcapHandle.getNextPacket().getPayload().getPayload().getPayload().getRawData();
    }
}
