/**
 * KineticParse.java
 */
package adsbnet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.sql.Timestamp;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This is a thread that reads the Kinetic Format TCP data.
 *
 * <p>
 * It Parses the socket data, with each line being terminated with a
 * &lt;CR&gt;&lt;LF&gt; There are several command formats to decode.
 *
 * <p>
 * We are only interested in the MSG 1-7 data, and toss out the rest.
 *
 * <p>
 * Decode the messages into their object types, and then push the objects onto
 * the msgQueue.
 *
 * @author Steve Sampson, January 2020
 */
public final class KineticParse extends Thread {

    private final int HEXIDENT = 4;
    private final int CALLSIGN = 10;
    private final int ALTITUDE = 11;
    private final int GSPEED = 12;
    private final int GTRACK = 13;
    private final int LATITUDE = 14;
    private final int LONGITUDE = 15;
    private final int VRATE = 16;
    private final int SQUAWK = 17;
    private final int ALERT = 18;
    private final int EMERG = 19;
    private final int SPI = 20;
    private final int GROUND = 21;
    //
    private static final long RATE1 = 30L * 1000L;              // 30 seconds
    private static final long RATE2 = 5L * 1000L;               // 5 seconds
    //
    private final ZuluMillis zulu;
    private Socket connection;
    private BufferedReader line;
    //
    private final Thread socketReceive;
    //
    private boolean shutdown;
    private boolean sbsStatus;
    private boolean remoteStatus;
    //
    private final ConcurrentHashMap<String, Track> trackReports;
    private final ConcurrentHashMap<Long, HeartBeat> beatReports;
    private final ConcurrentHashMap<String, Track> wanqueue;
    //
    private final Config config;
    private final GUI gui;
    //
    private DatagramSocket socket;
    private InputStream input;
    //
    private final Timer timer1;
    private final Timer timer2;
    //
    private final TimerTask task1;
    private final TimerTask task2;

    /**
     * Class constructor
     *
     * @param c is ASCII config file
     */
    public KineticParse(Config c) {
        config = c;
        zulu = new ZuluMillis();

        shutdown = false;

        trackReports = new ConcurrentHashMap<>();
        beatReports = new ConcurrentHashMap<>();
        wanqueue = new ConcurrentHashMap<>();

        if (config.getGUIEnable() == true) {
            gui = new GUI(this);
        } else {
            gui = null;
        }

        if (config.getUnicastHostCount() != 0) {
            openRemote();
        } else {
            remoteStatus = false;
        }
        
        openSBSSocket();

        task1 = new UpdateReports();
        timer1 = new Timer();
        timer1.scheduleAtFixedRate(task1, 0L, RATE1);

        task2 = new UpdateTrackQuality();
        timer2 = new Timer();
        timer2.scheduleAtFixedRate(task2, 10L, RATE2);

        socketReceive = new Thread(this);
        socketReceive.setName("KineticParse");
        socketReceive.setPriority(Thread.NORM_PRIORITY);
        socketReceive.start();

        /*
         * Show the GUI if enabled
         */
        if (config.getGUIEnable() == true) {
            gui.setVisible(true);
        }
    }

    public boolean getSBSStatus() {
        return sbsStatus;
    }

    public boolean getRemoteStatus() {
        return remoteStatus;
    }

    public void openRemote() {
        try {
            socket = new DatagramSocket(config.getUnicastPort());
            socket.setSoTimeout(10);    // 10ms
        } catch (SocketException e) {
            remoteStatus = false;
            return;
        }
        
        remoteStatus = true;
    }
    
    /**
     * A method to open Basestation socket connection
     */
    public void openSBSSocket() {
        try {
            connection = new Socket(config.getStationHost(), config.getStationPort());
            input = connection.getInputStream();
            line = new BufferedReader(new InputStreamReader(input));
        } catch (IOException e) {
            sbsStatus = false;
            return;
        }

        sbsStatus = true;
    }

    private void closeRemote() {
        remoteStatus = false;
        
        try {
            socket.close();
        } catch (Exception e) {
            // don't care
        }
    }
    
    /**
     * Method to close down the network TCP interface
     */
    private void closeSBSSocket() {
        sbsStatus = false;

        try {
            line.close();
            input.close();
            connection.close();
        } catch (IOException e) {
            // don't care
        }
    }

    /*
     * This will look through the Track and Beat tables and delete entries that
     * are over two minutes old.  In that case the target has probably landed
     * or faded-out from coverage, or in the case of Heartbeats - the site went away.
     */
    private class UpdateReports extends TimerTask {

        CopyOnWriteArrayList<Track> idRpt;
        CopyOnWriteArrayList<HeartBeat> beatRpt;

        @Override
        public void run() {
            long currentTime = zulu.getUTCTime();
            long delta;

            idRpt = getTrackHashTable();

            for (Track id : idRpt) {
                // find the reports that haven't been updated in 2 minutes
                delta = Math.abs(currentTime - id.getUpdateTime());

                if (delta >= 120000L) {
                    removeTrackReportsVal(id.getAircraftID());
                }
            }

            beatRpt = getBeatHashTable();

            for (HeartBeat b : beatRpt) {
                // find the beat reports that haven't been updated in 1 minute
                delta = Math.abs(currentTime - b.getUpdateTime());

                if (delta > 60000L) {
                    removeBeatReportsVal(b.getStationID());
                }
            }

            yield();
        }
    }

    /*
     * This will look through the Track local table and decrement track quality
     * every 30 seconds that the lat/lon position isn't updated. This timer task
     * is run every 5 seconds.
     */
    private class UpdateTrackQuality extends TimerTask {

        private CopyOnWriteArrayList<Track> idRpt;
        private long delta;
        private long currentTime;

        @Override
        public void run() {
            currentTime = zulu.getUTCTime();
            delta = 0L;
            String acid;

            idRpt = getTrackHashTable();

            for (Track id : idRpt) {
                try {
                    acid = id.getAircraftID();

                    if ((id != (Track) null) && (id.getTrackQuality() > 0)) {
                        // find the idStatus reports that haven't been position updated in 30 seconds
                        delta = Math.abs(currentTime - id.getUpdatePositionTime());

                        if (delta >= 30000L) {
                            id.decrementTrackQuality();
                            id.setUpdateTime(currentTime);
                            putTrackReportsVal(acid, id);   // overwrite
                        }
                    }
                } catch (NoSuchElementException e1) {
                    System.err.println("KineticParse::updateTrackQuality Exception during iteration " + e1.toString());
                }
            }

            yield();
        }
    }

    public void close() {
        timer1.cancel();
        timer2.cancel();
        shutdown = true;

        if (config.getGUIEnable() == true) {
            gui.close();
        }

        closeSBSSocket();
        closeRemote();
    }

    /**
     * Method to make a copy of the Track objects
     *
     * @return a vector containing a copy of the Track objects
     */
    public CopyOnWriteArrayList<Track> getTrackHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().forEach((trk) -> {
            result.add(trk);
        });

        return result;
    }

    /**
     * Method to make a copy of the Local Track objects
     *
     * @return a vector containing a copy of the Local Track objects
     */
    public CopyOnWriteArrayList<Track> getTrackLocalHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().stream().filter((id) -> (id.getTrackType() == Track.TRACK_LOCAL)).forEachOrdered((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to make a copy of all modified Track objects
     *
     * @return vector containing a copy of the modified Track objects
     */
    public CopyOnWriteArrayList<Track> getTrackMultiUpdatedHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().stream().filter((id) -> (id.getMultiUpdated() == true)).map((id) -> {
            // reset the update boolean

            id.setMultiUpdated(false);
            return id;
        }).forEachOrdered((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to make a copy of all modified Track objects
     *
     * @return vector containing a copy of the modified Track objects
     */
    public CopyOnWriteArrayList<Track> getTrackZeroUpdatedHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().stream().filter((id) -> (id.getZeroUpdated() == true)).map((id) -> {
            // reset the update boolean

            id.setZeroUpdated(false);
            return id;
        }).forEachOrdered((id) -> {
            result.add(id);
        });

        return result;
    }
    
    /**
     * Method to make a copy of the modified LOCAL Track objects
     *
     * @return a vector containing a copy of the modified Track objects
     */
    public CopyOnWriteArrayList<Track> getLocalTrackUpdatedHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().stream().filter((id) -> ((id.getMultiUpdated() == true) && (id.getTrackType() == Track.TRACK_LOCAL))).map((id) -> {
            // reset the update boolean

            id.setMultiUpdated(false);
            return id;
        }).forEachOrdered((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to make a copy of the modified REMOTE Track objects
     *
     * @return a vector containing a copy of the modified REMOTE Track objects
     */
    public CopyOnWriteArrayList<Track> getRemoteTrackUpdatedHashTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        trackReports.values().stream().filter((id) -> ((id.getMultiUpdated() == true) && (id.getTrackType() == Track.TRACK_REMOTE))).map((id) -> {
            // reset the update boolean

            id.setMultiUpdated(false);
            return id;
        }).forEachOrdered((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to return the total number of Track objects in the table
     * Positional Targets and Non-Positional Targets
     *
     * @return an integer Representing the total number of Track objects in the
     * table
     */
    public int getTrackReportsSize() {
        return trackReports.size();
    }

    /**
     * Method to return the Track object of a specified Aircraft ID (ACID) or
     * null if not found
     *
     * @param acid a string Representing the Mode-S code for the Track requested
     * @return a track object Representing the Mode-S code requested or null if
     * none found
     */
    public Track getTrackReportsVal(String acid) {
        Track trk = (Track) null;

        try {
            trk = (Track) trackReports.get(acid);
            return trk;
        } catch (NullPointerException e) {
            System.err.println("KineticParse::getTrackReportsVal Exception during get " + e.toString());
        }

        return trk;
    }

    /**
     * Method to put a Track object into the Track table
     *
     * @param acid a string Representing the Mode-S key into the table
     * @param id a track object Representing the Mode-S track
     */
    public void putTrackReportsVal(String acid, Track id) {
        try {
            trackReports.put(acid, id);
        } catch (NullPointerException e) {
            System.err.println("KineticParse::putTrackReportsVal Exception during put " + e.toString());
        }

        if (config.getGUIEnable() == true) {
            gui.setTrackCount(trackReports.size());
        }
    }

    /**
     * Method to remove a Track object from the Track table
     *
     * @param acid a string Representing the Mode-S key into the table
     */
    public void removeTrackReportsVal(String acid) {
        try {
            trackReports.remove(acid);
        } catch (NullPointerException e) {
            System.err.println("KineticParse::removeTrackReportsVal Exception during remove " + e.toString());
        }

        if (config.getGUIEnable() == true) {
            gui.setTrackCount(trackReports.size());
        }
    }

    /**
     * Method to return a copy of all Heartbeat table entries
     *
     * @return a vector Representing heartbeat table entries
     */
    public CopyOnWriteArrayList<HeartBeat> getBeatHashTable() {
        CopyOnWriteArrayList<HeartBeat> result = new CopyOnWriteArrayList<>();

        beatReports.values().forEach((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to remove a Track object from the Track table
     *
     * @param val a string Representing the Mode-S key into the table
     */
    public void removeBeatReportsVal(long val) {
        try {
            beatReports.remove(val);
        } catch (NullPointerException e) {
            System.err.println("KineticParse::removeBeatReportsVal Exception during remove " + e.toString());
        }
    }

    /**
     * Method to return the size of the heartbeat table
     *
     * @return an integer Representing the number of heartbeat table entries
     */
    public int getBeatReportsSize() {
        return beatReports.size();
    }

    /**
     * Method to return the Site Name given the StationID
     *
     * @param stationID a long Representing the Participant Unit (PU) ID
     * @return a string Representing the site name or a blank name
     */
    public String getSiteName(long stationID) {
        String name = "";

        try {
            if (beatReports.containsKey(stationID)) {
                name = beatReports.get(stationID).getStationName();
            }
        } catch (Exception e) {
            System.err.println("KineticParse::getSiteName Exception during containsKey/get " + e.toString());
        }

        return name;
    }

    /**
     * Method to return the number of WAN Track objects in the table
     *
     * @return an integer Representing the number of Track objects in the table
     */
    public int getWANQueueSize() {
        return wanqueue.size();
    }

    /**
     * Method to make a copy of the WAN Track objects
     *
     * @return a vector containing a copy of the Track objects
     */
    public CopyOnWriteArrayList<Track> getWANQueueTable() {
        CopyOnWriteArrayList<Track> result = new CopyOnWriteArrayList<>();

        wanqueue.values().forEach((id) -> {
            result.add(id);
        });

        return result;
    }

    /**
     * Method to put a Track object into the WAN Track table
     *
     * @param acid a string Representing the Mode-S key into the table
     * @param id a track object Representing the Mode-S track
     */
    public void putWANQueueVal(String acid, Track id) {
        try {
            wanqueue.put(acid, id);
        } catch (NullPointerException e) {
            System.err.println("KineticParse::putWANQueueVal Exception during put " + e.toString());
        }
    }

    /**
     * Method to remove a Track object from the WAN Track table
     *
     * @param acid a string Representing the Mode-S key into the table
     */
    public void removeWANQueueVal(String acid) {
        try {
            wanqueue.remove(acid);
        } catch (NullPointerException e) {
            System.err.println("KineticParse::removeWANQueueVal Exception during remove " + e.toString());
        }
    }

    /**
     * Method to remove all values from the WAN Track table
     */
    public void removeWANQueueTable() {
        wanqueue.clear();
    }

    private void decodeRemotePacket(String ip, String data) {
        Track id, old;
        String[] token;

        // process it
        token = data.split(",");   // Tokenize the data input line

        try {
            if (token[0].equals("STA")) {
                long stationID = (long) Long.parseLong(token[1].trim());
                long remoteTime = Timestamp.valueOf(token[2].trim()).getTime();

                /*
                 * Most people don't sync their clocks to NTP
                 * So this lets you see how far they are off
                 */
                long updateTime = zulu.getUTCTime();
                long diffTime = updateTime - remoteTime;

                String stationName = token[3].trim();
                double stationLat = Double.parseDouble(token[4].trim());
                double stationLon = Double.parseDouble(token[5].trim());
                long ntracks = (long) Long.parseLong(token[6].trim());

                /*
                 * This will either create an entry or update one
                 * that already exists
                 */
                if (beatReports.containsKey(stationID)) {
                    HeartBeat tmp = beatReports.get(stationID);
                    tmp.setStationName(stationName);        // Probably the same
                    tmp.setStationIP(ip);
                    tmp.setUpdateTime(updateTime);
                    tmp.incrementBeatCount();
                    tmp.setTrackCount(ntracks);
                    tmp.setDiffTime(diffTime);
                    beatReports.put(stationID, tmp);
                } else {
                    beatReports.put(stationID, new HeartBeat(stationName, stationID, stationLat, stationLon, diffTime, ntracks));
                }
            } else if (token[0].equals("TRK")) {
                String acid = token[3];
                long stationID = (long) Long.parseLong(token[1].trim());

                /*
                 * Check to make sure someone isn't sending me my own tracks
                 */
                if (stationID != config.getHomeID()) {

                    /*
                     * See if this ACID is on the table already
                     */
                    if ((old = id = getTrackReportsVal(acid)) == (Track) null) {
                        try {
                            id = new Track();
                        } catch (Exception e) {
                            System.err.println("KineticParse::decode exception: Unable to allocate a Track " + e.toString());
                            return;     // we're screwed
                        }
                    }

                    id.setSiteID(stationID);
                    id.setAircraftID(acid);

                    if (beatReports.containsKey(stationID)) {
                        id.setSiteIP(beatReports.get(stationID).getStationIP());
                    }

                    /*
                     * Detected time is the actual UTC time the target was detected
                     * at the remote site.  This time may be unsynchronized.
                     */
                    id.setDetectedTime(Timestamp.valueOf(token[2]).getTime());

                    if (!token[4].equals("")) {
                        id.setCallsign(token[4]);
                    }

                    int Squawk = -999;
                    if (!token[5].equals("")) {
                        Squawk = Integer.parseInt(token[5]);
                    }

                    id.setSquawk(Squawk);

                    int vrate = -999;
                    if (!token[6].equals("")) {
                        vrate = Integer.parseInt(token[6]);
                    }

                    id.setVerticalRate(vrate);

                    double gt = -999.0;
                    if (!token[7].equals("")) {
                        gt = Double.parseDouble(token[7]);
                    }

                    id.setGroundTrack(gt);

                    double gs = -999.0;
                    if (!token[8].equals("")) {
                        gs = Double.parseDouble(token[8]);
                    }

                    id.setGroundSpeed(gs);

                    int alt = -999;
                    if (!token[9].equals("")) {
                        alt = Integer.parseInt(token[9]);
                    }

                    id.setAltitude(alt);

                    int quality = 0;
                    if (!token[16].equals("")) {
                        quality = Integer.parseInt(token[16]);
                    }

                    id.setTrackQuality(quality);

                    double lat = 0.0;
                    if (!token[10].equals("")) {
                        lat = Double.parseDouble(token[10]);
                    }

                    double lon = 0.0;
                    if (!token[11].equals("")) {
                        lon = Double.parseDouble(token[11]);
                    }

                    boolean Alert = token[12].equals("-1");
                    boolean Emergency = token[13].equals("-1");
                    boolean spif = token[14].equals("-1");
                    boolean OnGround = token[15].equals("-1");

                    id.setAlert(Alert, Emergency, spif);
                    id.setOnGround(OnGround);

                    id.setTrackType(Track.TRACK_REMOTE);

                    /*
                     * If track quality of REMOTE track is less than current LOCAL track,
                     * then go ahead and update everything EXCEPT position.
                     */
                    if (old != (Track) null) {
                        // Track exists already
                        if (old.getTrackType() == Track.TRACK_LOCAL) {
                            // Track is currently a LOCAL track
                            if (quality <= old.getTrackQuality()) {
                                // REMOTE quality is less than LOCAL
                                if (old.getLatitude() != 0.0 && old.getLongitude() != 0.0) {
                                    // Old Lat/Lon isn't 0.0 so keep track position LOCAL
                                    id.setTrackType(Track.TRACK_LOCAL);
                                    id.setSiteID(config.getHomeID());
                                    id.setPosition(old.getLatitude(), old.getLongitude());
                                } else if (lat != -999.0 && lon != -999.0) {
                                    // Old Lat/Lon is 0.0, so use the new values
                                    // only if they aren't 0.0 as well
                                    id.setPosition(lat, lon);
                                }
                            }
                        } else if (lat != -999.0 && lon != -999.0) {
                            // Track is REMOTE
                            id.setPosition(lat, lon);
                        }
                    } else if (lat != -999.0 && lon != -999.0) {
                        // Old Track doesn't exist
                        id.setPosition(lat, lon);
                    }

                    /*
                     * This is our site time
                     */
                    id.setUpdateTime(zulu.getUTCTime());
                    putTrackReportsVal(acid, id);
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("KineticParse::decodePacket Exception during conversion \"" + data + "\"" + e.toString());
        }
    }

    /*
     * Thread to wait for socket data and push decoded objects onto track queue
     */
    @Override
    public void run() {
        Track id, old;
        long currentTime;
        String acid;
        String data = "";
        String ip;
        String temp;
        DatagramPacket dinput;
        String callsign;
        int altitude;
        int verticalRate;
        int squawk;
        int gnd;
        double groundSpeed;
        double groundTrack;
        double latitude;
        double longitude;
        boolean isOnGround;
        boolean alert;
        boolean emergency;
        boolean spi;
        String[] token;
        int type;

        while (shutdown == false) {
            /*
             * First, see if the remote PU's have anything
             */
            byte[] buff = new byte[512];

            if (remoteStatus == true) {
                while (true) {
                    try {
                        dinput = new DatagramPacket(buff, buff.length);
                        socket.receive(dinput);  // blocks for 10ms max
                        ip = dinput.getAddress().getHostAddress();
                        data = new String(buff, 0, dinput.getLength(), "US-ASCII");
                        decodeRemotePacket(ip, data);
                    } catch (IOException e) {
                        // timeout
                        break;
                    }
                }
            }

            // Now check Basestation
            if (sbsStatus == true) {
                try {
                    while (line.ready()) {
                        data = line.readLine();
                        currentTime = zulu.getUTCTime();

                        if (data.startsWith("MSG")) {
                            if (!data.startsWith("MSG,8")) {
                                // modesmixer2 sometimes doesn't output ONGROUND
                                // so this will make it skip
                                token = data.split(",", -2);   // Tokenize the data input line

                                type = Integer.parseInt(token[1]);
                                acid = token[HEXIDENT];

                                /*
                                 * See if this ACID is on the table already
                                 */
                                if ((old = id = getTrackReportsVal(acid)) == (Track) null) {
                                    try {
                                        id = new Track();
                                    } catch (Exception e) {
                                        System.err.println("KineticParse::run exception: Unable to allocate a Track " + e.toString());
                                        break;
                                    }
                                }

                                id.setAircraftID(acid);
                                id.setSiteID(config.getHomeID());
                                id.setTrackType(Track.TRACK_LOCAL);

                                altitude = -999;
                                squawk = -999;

                                switch (type) {
                                    case 1:
                                        try {
                                            callsign = token[CALLSIGN].replace('@', ' ');  // This symbol @ means null
                                        } catch (Exception e) {
                                            /*
                                             * gomer has no callsign inserted, but broadcasting type 1 callsign
                                             */
                                            callsign = ""; // so replace with a null
                                        }

                                        id.setCallsign(callsign);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType1();
                                        }
                                        break;
                                    case 2:
                                        if (!token[ALTITUDE].equals("")) {
                                            altitude = Integer.parseInt(token[ALTITUDE]);
                                            id.setAltitude(altitude);
                                        }

                                        if (!token[GSPEED].equals("")) {
                                            groundSpeed = Double.parseDouble(token[GSPEED]);
                                            id.setGroundSpeed(groundSpeed);
                                        }

                                        if (!token[GTRACK].equals("")) {
                                            groundTrack = Double.parseDouble(token[GTRACK]);
                                            id.setGroundTrack(groundTrack);
                                        }

                                        if (!token[LATITUDE].equals("")) {
                                            latitude = Double.parseDouble(token[LATITUDE]);
                                        } else {
                                            latitude = 0.0;
                                        }

                                        if (!token[LONGITUDE].equals("")) {
                                            longitude = Double.parseDouble(token[LONGITUDE]);
                                        } else {
                                            longitude = 0.0;
                                        }

                                        temp = token[GROUND];

                                        if (!temp.equals("")) {
                                            gnd = Integer.parseInt(temp);

                                            isOnGround = (altitude == 0) || (gnd == -1);
                                        } else {
                                            isOnGround = false;
                                        }

                                        /*
                                         * If track quality of REMOTE track is less than current LOCAL track,
                                         * then go ahead and update everything EXCEPT position.
                                         */
                                        if (old != (Track) null) {
                                            // Track exists already
                                            if (old.getTrackType() == Track.TRACK_REMOTE) {
                                                // Track is currently a REMOTE track

                                                if (old.getLatitude() != 0.0 && old.getLongitude() != 0.0) {
                                                    // Old Lat/Lon isn't 0.0 so keep track position REMOTE
                                                    id.setTrackType(Track.TRACK_REMOTE);
                                                    id.setSiteID(old.getSiteID());
                                                    id.setPosition(old.getLatitude(), old.getLongitude());
                                                } else if (latitude != 0.0 && longitude != 0.0) {
                                                    // Old Lat/Lon is 0.0, so use the new values
                                                    // only if they aren't 0.0 as well
                                                    id.setPosition(latitude, longitude);
                                                }
                                            } else if (latitude != 0.0 && longitude != 0.0) {
                                                // Track is LOCAL
                                                id.setPosition(latitude, longitude);
                                            }
                                        } else if (latitude != 0.0 && longitude != 0.0) {
                                            // Old Track doesn't exist
                                            id.setPosition(latitude, longitude);
                                        }

                                        id.setOnGround(isOnGround);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType2();
                                        }
                                        break;
                                    case 3:
                                        if (!token[ALTITUDE].equals("")) {
                                            altitude = Integer.parseInt(token[ALTITUDE]);

                                            if (altitude < 100) {
                                                // this is probably bullcrap
                                                altitude = -999; // mark it ignore
                                            }

                                            id.setAltitude(altitude);
                                        }

                                        if (!token[LATITUDE].equals("")) {
                                            latitude = Double.parseDouble(token[LATITUDE]);
                                        } else {
                                            latitude = 0.0;
                                        }

                                        if (!token[LONGITUDE].equals("")) {
                                            longitude = Double.parseDouble(token[LONGITUDE]);
                                        } else {
                                            longitude = 0.0;
                                        }

                                        temp = token[ALERT];

                                        if (!temp.equals("")) {
                                            alert = Integer.parseInt(temp) != 0;
                                        } else {
                                            alert = false;
                                        }

                                        temp = token[EMERG];

                                        if (!temp.equals("")) {
                                            emergency = Integer.parseInt(temp) != 0;
                                        } else {
                                            emergency = false;
                                        }

                                        temp = token[SPI];

                                        if (!temp.equals("")) {
                                            spi = Integer.parseInt(temp) != 0;
                                        } else {
                                            spi = false;
                                        }

                                        temp = token[GROUND];

                                        if (!temp.equals("")) {
                                            gnd = Integer.parseInt(temp);

                                            isOnGround = (gnd == -1);
                                        } else {
                                            isOnGround = false;
                                        }

                                        /*
                                         * If track quality of REMOTE track is less than current LOCAL track,
                                         * then go ahead and update everything EXCEPT position.
                                         */
                                        if (old != (Track) null) {
                                            // Track exists already
                                            if (old.getTrackType() == Track.TRACK_REMOTE) {
                                                // Track is currently a REMOTE track

                                                if (old.getLatitude() != 0.0 && old.getLongitude() != 0.0) {
                                                    // Old Lat/Lon isn't 0.0 so keep track position REMOTE
                                                    id.setTrackType(Track.TRACK_REMOTE);
                                                    id.setSiteID(old.getSiteID());
                                                    id.setPosition(old.getLatitude(), old.getLongitude());
                                                } else if (latitude != 0.0 && longitude != 0.0) {
                                                    // Old Lat/Lon is 0.0, so use the new values
                                                    // only if they aren't 0.0 as well
                                                    id.setPosition(latitude, longitude);
                                                }
                                            } else if (latitude != 0.0 && longitude != 0.0) {
                                                // Track is LOCAL
                                                id.setPosition(latitude, longitude);
                                            }
                                        } else if (latitude != 0.0 && longitude != 0.0) {
                                            // Old Track doesn't exist
                                            id.setPosition(latitude, longitude);
                                        }

                                        id.setOnGround(isOnGround);
                                        id.setAlert(alert, emergency, spi);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType3();
                                        }
                                        break;
                                    case 4:
                                        if (!token[GSPEED].equals("")) {
                                            groundSpeed = Double.parseDouble(token[GSPEED]);
                                            id.setGroundSpeed(groundSpeed);
                                        }

                                        if (!token[GTRACK].equals("")) {
                                            groundTrack = Double.parseDouble(token[GTRACK]);
                                            id.setGroundTrack(groundTrack);
                                        }

                                        if (!token[VRATE].equals("")) {
                                            verticalRate = Integer.parseInt(token[VRATE]);
                                            id.setVerticalRate(verticalRate);
                                        }

                                        if (config.getGUIEnable() == true) {
                                            gui.incType4();
                                        }
                                        break;
                                    case 5:
                                        if (!token[ALTITUDE].equals("")) {
                                            altitude = Integer.parseInt(token[ALTITUDE]);
                                            id.setAltitude(altitude);
                                        }

                                        temp = token[ALERT];

                                        if (!temp.equals("")) {
                                            alert = Integer.parseInt(temp) != 0;
                                        } else {
                                            alert = false;
                                        }

                                        temp = token[SPI];

                                        if (!temp.equals("")) {
                                            spi = Integer.parseInt(temp) != 0;
                                        } else {
                                            spi = false;
                                        }

                                        temp = token[GROUND];

                                        if (!temp.equals("")) {
                                            gnd = Integer.parseInt(temp);

                                            isOnGround = (gnd == -1);
                                        } else {
                                            isOnGround = false;
                                        }

                                        id.setAlert(alert, false, spi);
                                        id.setOnGround(isOnGround);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType5();
                                        }
                                        break;
                                    case 6:
                                        if (!token[ALTITUDE].equals("")) {
                                            altitude = Integer.parseInt(token[ALTITUDE]);
                                        }

                                        if (altitude < 100) {
                                            altitude = -999;
                                        }

                                        if (!token[SQUAWK].equals("")) {
                                            squawk = Integer.parseInt(token[SQUAWK]);
                                        }

                                        temp = token[ALERT];

                                        if (!temp.equals("")) {
                                            alert = Integer.parseInt(temp) != 0;
                                        } else {
                                            alert = false;
                                        }

                                        temp = token[EMERG];

                                        if (!temp.equals("")) {
                                            emergency = Integer.parseInt(temp) != 0;
                                        } else {
                                            emergency = false;
                                        }

                                        temp = token[SPI];

                                        if (!temp.equals("")) {
                                            spi = Integer.parseInt(temp) != 0;
                                        } else {
                                            spi = false;
                                        }

                                        temp = token[GROUND];

                                        if (!temp.equals("")) {
                                            gnd = Integer.parseInt(temp);

                                            isOnGround = (gnd == -1);
                                        } else {
                                            isOnGround = false;
                                        }

                                        id.setAltitude(altitude);
                                        id.setSquawk(squawk);
                                        id.setAlert(alert, emergency, spi);
                                        id.setOnGround(isOnGround);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType6();
                                        }
                                        break;
                                    case 7:
                                        if (!token[ALTITUDE].equals("")) {
                                            altitude = Integer.parseInt(token[ALTITUDE]);

                                            if (altitude < 100) {
                                                altitude = -999;
                                            }

                                            id.setAltitude(altitude);
                                        }

                                        temp = token[GROUND];

                                        if (!temp.equals("")) {
                                            gnd = Integer.parseInt(temp);

                                            isOnGround = (gnd == -1);
                                        } else {
                                            isOnGround = false;
                                        }

                                        id.setOnGround(isOnGround);

                                        if (config.getGUIEnable() == true) {
                                            gui.incType7();
                                        }
                                }

                                id.setDetectedTime(currentTime);
                                id.setUpdateTime(currentTime);

                                putTrackReportsVal(acid, id);
                            }
                        }
                    }
                } catch (IOException | NumberFormatException e) {
                    System.err.print(data);
                    System.err.println(" KineticParse::run exception " + e.toString());
                }
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
            }
        }
    }
}
