import org.apache.commons.codec.binary.Hex;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.StringTokenizer;
import java.util.UUID;

public class Server implements ActionListener {

    //RTP variables:
    //----------------
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    DatagramPacket senddp; //UDP packet containing the video frames
    PacketsProvider packetsProvider;
    InetAddress ClientIPAddr;   //Client IP address
    int RTP_dest_port = 0;      //destination port for RTP packets  (given by the RTSP Client)
    int RTSP_dest_port = 0;

    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted

    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    static int FRAME_PERIOD = 100; //Frame period of the video to stream, in ms
    static int VIDEO_LENGTH = 500; //length of the video in frames

    Timer timer;    //timer used to send the images at the video frame rate
    byte[] buf;     //buffer used to store the images to send to the client
    int sendDelay;  //the delay to send images over the wire. Ideally should be
    //equal to the frame rate of the video file, but may be
    //adjusted when congestion is detected.

    //RTSP variables
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    //rtsp message types
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;
    final static int DESCRIBE = 7;
    final static int OPTIONS = 8;

    static int state; //RTSP Server state == INIT or READY or PLAY
    Socket RTSPsocket; //socket used to send/receive RTSP messages

    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String RTSPid = UUID.randomUUID().toString(); //ID of the RTSP session
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session


    //RTCP variables
    //----------------
    static int RTCP_RCV_PORT = 0; //port where the client will receive the RTP packets
    final static String CRLF = "\r\n";
//    DatagramSocket RTCPsocket;

    //--------------------------------
    //Constructor
    //--------------------------------
    public Server() {

        //init RTP sending Timer
        sendDelay = FRAME_PERIOD;
        timer = new Timer(sendDelay, this);
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //allocate memory for the sending buffer
        buf = new byte[20000];

        packetsProvider = PacketsProvider.getInstance();

    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String[] argv) throws Exception {

        if (argv.length != 1) {
            System.err.println("File path not specified");
            System.exit(1);
        }

//        PacketsProvider.setFilePath("src/main/resources/test3.pcap");
        PacketsProvider.setFilePath(argv[0]);
        //create a Server object
        Server server = new Server();

        //set RTSP socket port
        int RTSPport = 5540;
        server.RTSP_dest_port = RTSPport;

        //Initiate TCP connection with the client for the RTSP session
        ServerSocket listenSocket = new ServerSocket(RTSPport);
        server.RTSPsocket = listenSocket.accept();
        server.RTSPsocket.setKeepAlive(false);
        listenSocket.close();

        //Get Client IP address
        server.ClientIPAddr = server.RTSPsocket.getInetAddress();
        RTCP_RCV_PORT = server.RTSPsocket.getPort();

        //Initiate RTSPstate
        state = INIT;

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(server.RTSPsocket.getInputStream()));
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(server.RTSPsocket.getOutputStream()));

        int request_type;

        //loop to handle RTSP requests
        while (true) {
            //parse the request
            request_type = server.parseRequest(); //blocking

            if (request_type == OPTIONS) {
                //Send response
                server.sendResponse("Public: DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE, OPTIONS");

            } else if (request_type == SETUP) {
                //update RTSP state
                System.out.println("New RTSP state: READY");

                //Send response
                if (state != READY) {
                    server.RTPsocket = new DatagramSocket(RTCP_RCV_PORT);
                    server.sendResponse("Transport: RTP/AVP/TCP;unicast;interleaved=0-1" + CRLF + "Session: " + RTSPid + ";timeout=60");
                } else
                    server.sendResponse("Transport: RTP/AVP/TCP;unicast;interleaved=2-3" + CRLF + "Session: " + RTSPid + ";timeout=60");

                state = READY;

            } else if ((request_type == PLAY) && (state == READY)) {
                //send back response
                server.sendResponse();
                //start timer
                server.timer.start();
                //update state
                state = PLAYING;
                System.out.println("New RTSP state: PLAYING");

            } else if ((request_type == PAUSE) && (state == PLAYING)) {
                //send back response
                server.sendResponse();
                //stop timer
                server.timer.stop();
                //update state
                state = READY;
                System.out.println("New RTSP state: READY");

            } else if (request_type == TEARDOWN) {
                //send back response
                server.sendResponse();
                //stop timer
                server.timer.stop();
                //close socket
                server.RTSPsocket.close();

                System.exit(0);
            } else if (request_type == DESCRIBE) {
                System.out.println("Received DESCRIBE request");
                server.sendDescribe();
            }
        }
    }

    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    private int parseRequest() {
        int request_type = -1;
        try {
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            if (RequestLine == null || RequestLine.isEmpty())
                return request_type;
            System.out.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ("SETUP".equals(request_type_string))
                request_type = SETUP;
            else if ("PLAY".equals(request_type_string))
                request_type = PLAY;
            else if ("PAUSE".equals(request_type_string))
                request_type = PAUSE;
            else if ("TEARDOWN".equals(request_type_string))
                request_type = TEARDOWN;
            else if ("DESCRIBE".equals(request_type_string))
                request_type = DESCRIBE;
            else if ("OPTIONS".equals(request_type_string))
                request_type = OPTIONS;
            else return request_type;

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());

            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            tokens = new StringTokenizer(LastLine);
            if (request_type == SETUP) {
                while (!LastLine.contains("client_port"))
                    LastLine = RTSPBufferedReader.readLine();
                String port = LastLine.substring(LastLine.lastIndexOf("=") + 1,
                        LastLine.indexOf('-'));//, LastLine.lastIndexOf("="))).split("-");
                RTP_dest_port = (Integer.parseInt(port));
//                RTP_dest_port = Integer.parseInt(port[1]));
                System.out.println("UDP PORT: " + RTP_dest_port);
            } else if (request_type == DESCRIBE) {
                tokens.nextToken();
                String describeDataType = tokens.nextToken();
            } else if (request_type == OPTIONS) {
            } else {
                //otherwise LastLine will be the SessionId line
                tokens.nextToken(); //skip Session:
                if (!"".equals(RTSPid))
                    RTSPid = tokens.nextToken();
            }

            //read empty line
            do {
                RequestLine = RTSPBufferedReader.readLine();
            } while (!RequestLine.equals(""));
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            ex.printStackTrace();
            System.exit(0);
        }

        return (request_type);
    }

    // Creates a DESCRIBE response string in SDP format for current media
    private String describe() {

        String s = "RTSP/1.0 200 OK" + CRLF +
                "CSeq: 3" + CRLF +
                "Server: Wowza Streaming Engine 4.7.5.01 build21752" + CRLF +
                "Cache-Control: no-cache" + CRLF +
                "Content-Length: 581" + CRLF +
                "Content-Base: rtsp://localhost:5540/vod/mp4:BigBuckBunny_115k.mov/" + CRLF +
                "Content-Type: application/sdp" + CRLF +
                "Session: 1823687535;timeout=60" + CRLF + CRLF +
                "v=0" + CRLF +
                "o=- 1823687535 1823687535 IN IP4 127.0.0.1" + CRLF +
                "s=BigBuckBunny_115k.mov" + CRLF +
                "c=IN IP4 127.0.0.1" + CRLF +
                "t=0 0" + CRLF +
                "a=sdplang:en" + CRLF +
                "a=range:npt=0- 596.48" + CRLF +
                "a=control:*" + CRLF +
                "m=audio 0 RTP/AVP 96" + CRLF +
                "a=rtpmap:96 mpeg4-generic/12000/2" + CRLF +
                "a=fmtp:96 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1490" + CRLF +
                "a=control:trackID=1" + CRLF +
                "m=video 0 RTP/AVP 97" + CRLF +
                "a=rtpmap:97 H264/90000" + CRLF +
                "a=fmtp:97 packetization-mode=1;profile-level-id=42C01E;sprop-parameter-sets=Z0LAHtkDxWhAAAADAEAAAAwDxYuS,aMuMsg==" + CRLF +
                "a=cliprect:0,0,160,240" + CRLF +
                "a=framesize:97 240-160" + CRLF +
                "a=framerate:24.0" + CRLF +
                "a=control:trackID=2" + CRLF;
        return s;
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    private void sendResponse() {
        try {
            RTSPBufferedWriter.flush();
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
            RTSPBufferedWriter.write("Server: localhost" + CRLF);
            RTSPBufferedWriter.write("Cache-Control: no-cache" + CRLF);
            RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            RTSPBufferedWriter.write(CRLF);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    private void sendResponse(String message) {
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
            RTSPBufferedWriter.write("Server: localhost" + CRLF);
            RTSPBufferedWriter.write("Cache-Control: no-cache" + CRLF);
            RTSPBufferedWriter.write(message + CRLF);
            RTSPBufferedWriter.write(CRLF);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    private void sendDescribe() {
        String des = describe();
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
            RTSPBufferedWriter.write("Server: localhost" + CRLF);
            RTSPBufferedWriter.write("Cache-Control: no-cache" + CRLF);
            RTSPBufferedWriter.write(des);
            RTSPBufferedWriter.write(CRLF);
            RTSPBufferedWriter.flush();
            System.out.println("RTSP Server - Sent response to Client.");
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    @Override
    //------------------------
    //Handler for timer
    //------------------------
    public void actionPerformed(ActionEvent e) {

        //if the current image nb is less than the length of the video
        try {
            byte[] packet = packetsProvider.getNextRtpPacket();
            if (packet != null) {
                //send the packet as a DatagramPacket over the UDP socket
//                senddp = new DatagramPacket(packet, packet.length, ClientIPAddr, RTCP_RCV_PORT);
//                RTPsocket.send(senddp);
//
//                RTSPBufferedWriter.write(Arrays.toString(packet));
//                RTSPBufferedWriter.flush();
//                System.out.println(Hex.encodeHexString(packet));
                RTSPsocket.getOutputStream().write(packet);
                RTSPsocket.getOutputStream().flush();
            } else {
                //if we have reached the end of the video file, stop the timer
                timer.stop();
            }
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }
}
