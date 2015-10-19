/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.hammer;

import com.google.common.collect.ImmutableList;
import net.java.sip.communicator.impl.osgi.framework.launch.FrameworkFactoryImpl;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.JingleIQ;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.JingleIQProvider;
import org.jitsi.hammer.extension.MediaProvider;
import org.jitsi.hammer.extension.SsrcProvider;
import org.jitsi.hammer.stats.FakeUserStats;
import org.jitsi.hammer.stats.HammerStats;
import org.jitsi.hammer.utils.MediaDeviceChooser;
import org.jitsi.util.Logger;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.provider.ProviderManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Thomas Kuntz
 *
 * The <tt>Hammer</tt> class is the core class of the jitsi-hammer project.
 * This class will try to create N virtual users to a XMPP server then to
 * a MUC chatroom created by JitMeet (https://jitsi.org/Projects/JitMeet).
 *
 * Each virtual user after succeeding in the connection to the JitMeet MUC
 * receive an invitation to a audio/video conference. After receiving the
 * invitation, each virtual user will positively reply to the invitation and
 * start sending audio and video data to the jitsi-videobridge handling the
 * conference.
 */
public class Hammer
{
    /**
     * The <tt>Logger</tt> used by the <tt>Hammer</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(Hammer.class);

    /**
     * The base of the nickname use by all the virtual users this Hammer
     * will create.
     */
    private final String nickname;

    /**
     * The information about the XMPP server to which all virtual users will
     * try to connect.
     */
    private final HostInfo serverInfo;

    /**
     * The <tt>MediaDeviceChooser</tt> that will be used by all the
     * <tt>FakeUser</tt> to choose their <tt>MediaDevice</tt>
     */
    private final MediaDeviceChooser mediaDeviceChooser;


    /**
     * The <tt>org.osgi.framework.launch.Framework</tt> instance which
     * represents the OSGi instance launched by this <tt>ComponentImpl</tt>.
     */
    private static Framework framework;

    /**
     * The <tt>Object</tt> which synchronizes the access to {@link #framework}.
     */
    private static final Object frameworkSyncRoot = new Object();

    /**
     * The locations of the OSGi bundles (or rather of the path of the class
     * files of their <tt>BundleActivator</tt> implementations).
     * An element of the <tt>BUNDLES</tt> array is an array of <tt>String</tt>s
     * and represents an OSGi start level.
     */
    private static final String[][] BUNDLES =
    {

        {
            "net/java/sip/communicator/impl/libjitsi/LibJitsiActivator"
        }
        //These bundles are used in Jitsi-Videobridge from which I copied
        //some code, but these bundle doesn't seems necessary for the hammer
        /*,
            {
                "net/java/sip/communicator/util/UtilActivator",
                "net/java/sip/communicator/impl/fileaccess/FileAccessActivator"
            },
            {
                "net/java/sip/communicator/impl/configuration/ConfigurationActivator"
            },
            {
                "net/java/sip/communicator/impl/resources/ResourceManagementActivator"
            },
            {
                "net/java/sip/communicator/util/dns/DnsUtilActivator"
            },
            {
                "net/java/sip/communicator/impl/netaddr/NetaddrActivator"
            },
            {
                "net/java/sip/communicator/impl/packetlogging/PacketLoggingActivator"
            },
            {
                "net/java/sip/communicator/service/gui/internal/GuiServiceActivator"
            },
            {
                "net/java/sip/communicator/service/protocol/media/ProtocolMediaActivator"
            },
            {
                "org/jitsi/hammer/HammerActivator"
            }*/
    };

    /**
     * The array containing all the <tt>FakeUser</tt> that this Hammer
     * handle, representing all the virtual user that will connect to the XMPP
     * server and start MediaStream with its jitsi-videobridge
     */
    private final ImmutableList<FakeUser> fakeUsers;

    /**
     * The <tt>HammerStats/tt> that will be used by this <tt>Hammer</tt>
     * to keep track of the streams' stats of all the <tt>FakeUser</tt>
     * etc..
     */
    private final HammerStats hammerStats;

    /**
     * The thread that run the <tt>HammerStats</tt> of this <tt>Hammer</tt>
     */
    private final Thread hammerStatsThread;

    /**
     * boolean used to know if the <tt>Hammer</tt> is started or not.
     * protected by the synchronized methods start and stop
     */
    private boolean started = false;


    /**
     * Instantiate a <tt>Hammer</tt> object with <tt>numberOfUser</tt> virtual
     * users that will try to connect to the XMPP server and its videobridge
     * contained in <tt>host</tt>.
     *
     * @param host The information about the XMPP server to which all
     * virtual users will try to connect.
     * @param mdc
     * @param nickname The base of the nickname used by all the virtual users.
     * @param numberOfUser The number of virtual users this <tt>Hammer</tt>
     * will create and handle.
     */
    public Hammer(HostInfo host, MediaDeviceChooser mdc, String nickname, int numberOfUser, boolean disableStats, String logFile)
    {
        this.nickname = nickname;
        this.serverInfo = host;
        this.mediaDeviceChooser = mdc;

        if (!disableStats) {
            File output = new File(logFile);
            try {
                OutputStream writer = new FileOutputStream(output);
                hammerStats = new HammerStats(writer);
                hammerStatsThread = new Thread(hammerStats);
            } catch (Exception e) {
                logger.error("Failed to create hammer stats file", e);
                throw new RuntimeException(e);
            }
        } else {
            hammerStats = null;
            hammerStatsThread = null;
        }

        ImmutableList.Builder<FakeUser> userListBuilder = ImmutableList.builder();
        for(int i = 0; i<numberOfUser; i++)
        {
            userListBuilder.add(new FakeUser(
                this.serverInfo,
                this.mediaDeviceChooser,
                this.nickname+"_"+i,
                !disableStats));
        }
        fakeUsers = userListBuilder.build();
        logger.info(String.format("Hammer created : %d fake users were created"
            + " with a base nickname %s", numberOfUser, nickname));
    }



    /**
     * Initialize the Hammer by launching the OSGi Framework and
     * installing/registering the needed bundle (LibJitis and more..).
     */
    public static void init()
    {
        /**
         * This code is a slightly modified copy of the one found in
         * startOSGi of the class ComponentImpl of jitsi-videobridge.
         *
         * This function run the activation of different bundle that are needed
         * These bundle are the one found in the <tt>BUNDLE</tt> array
         */
        synchronized (frameworkSyncRoot)
        {
            if (Hammer.framework != null)
                return;
        }

        logger.info("Start OSGi framework with the bundles : " + BUNDLES);
        FrameworkFactory frameworkFactory = new FrameworkFactoryImpl();
        Map<String, String> configuration = new HashMap<String, String>();
        BundleContext bundleContext = null;

        configuration.put(
            Constants.FRAMEWORK_BEGINNING_STARTLEVEL,
            Integer.toString(BUNDLES.length));

        Framework framework = frameworkFactory.newFramework(configuration);

        try
        {
            framework.init();

            bundleContext = framework.getBundleContext();

            for (int startLevelMinus1 = 0;
                startLevelMinus1 < BUNDLES.length;
                startLevelMinus1++)
            {
                int startLevel = startLevelMinus1 + 1;

                for (String location : BUNDLES[startLevelMinus1])
                {
                    Bundle bundle = bundleContext.installBundle(location);

                    if (bundle != null)
                    {
                        BundleStartLevel bundleStartLevel
                        = bundle.adapt(BundleStartLevel.class);

                        if (bundleStartLevel != null)
                            bundleStartLevel.setStartLevel(startLevel);
                    }
                }
            }

            framework.start();
        }
        catch (BundleException be)
        {
            throw new RuntimeException(be);
        }

        synchronized (frameworkSyncRoot)
        {
            Hammer.framework = framework;
        }

        logger.info("Add extension provider for :");
        ProviderManager manager = ProviderManager.getInstance();
        logger.info("Element name : " + MediaProvider.ELEMENT_NAME
            + ", Namespace : " + MediaProvider.NAMESPACE);
        manager.addExtensionProvider(
            MediaProvider.ELEMENT_NAME,
            MediaProvider.NAMESPACE,
            new MediaProvider());
        logger.info("Element name : " + SsrcProvider.ELEMENT_NAME
            + ", Namespace : " + SsrcProvider.NAMESPACE);
        manager.addExtensionProvider(
            SsrcProvider.ELEMENT_NAME,
            SsrcProvider.NAMESPACE,
            new SsrcProvider());
        logger.info("Element name : " + JingleIQ.ELEMENT_NAME
            + ", Namespace : " + JingleIQ.NAMESPACE);
        manager.addIQProvider(
            JingleIQ.ELEMENT_NAME,
            JingleIQ.NAMESPACE,
            new JingleIQProvider());
    }

    /**
     * Start the connection of all the virtual user that this <tt>Hammer</tt>
     * handles to the XMPP server(and then a MUC), using the <tt>Credential</tt>
     * given as arguments for the login.
     *
     * @param wait the number of milliseconds the Hammer will wait during the
     * start of two consecutive fake users.
     * @param credentials a list of <tt>Credentials</tt> used for the login
     * of the fake users.
     * @param overallStats enable or not the logging of the overall stats
     * computed at the end of the run.
     * @param allStats enable or not the logging of the all the stats collected
     * by the <tt>HammerStats</tt> during the run.
     * @param summaryStats enable or not the logging of the dummary stats
     * computed from all the streams' stats collected by the
     * <tt>HammerStats</tt> during the run.
     * @param statsPollingTime the number of seconds between two polling of stats
     * by the <tt>HammerStats</tt> run method.
     */
    public synchronized void start(
        int wait,
        List<Credential> credentials,
        boolean overallStats,
        boolean allStats,
        boolean summaryStats,
        int statsPollingTime)
    {
        if(wait <= 0) wait = 1;
        if(started)
        {
            logger.warn("Hammer already started");
            return;
        }

        if (credentials != null)
            startUsersWithCredentials(credentials, wait);
        else
            startUsersAnonymous(wait);
        this.started = true;
        logger.info("The Hammer has correctly been started");

        if (hammerStats != null)
            startStats(overallStats, allStats, summaryStats, statsPollingTime);
    }

    /**
     * Start all users using authenticated login.
     *
     * @param credentials a list of <tt>Credentials</tt> used for the login of
     * the fake users.
     * @param wait the number of milliseconds the Hammer will wait during the
     * start of two consecutive fake users.
     */
    private void startUsersWithCredentials(List<Credential> credentials, int wait) {
        logger.info("Starting the Hammer : starting all FakeUsers with username/password login");
        try {
            Iterator<Credential> credIt = credentials.iterator();
            Credential credential = null;
            boolean isFirst = true;

            for(FakeUser user : fakeUsers) {
                credential = credIt.next();
                user.start(credential.getUsername(),credential.getPassword());
                if(isFirst) {
                    user.createConference(this.serverInfo.getFocusUserJid());
                    isFirst = false;
                }
                if (hammerStats != null && user.getFakeUserStats() != null) {
                    hammerStats.addFakeUsersStats(user.getFakeUserStats());
                }
                Thread.sleep(wait);
            }
        } catch (XMPPException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start all fake users with anonymous login.
     * @param wait the number of milliseconds the Hammer will wait during the
     * start of two consecutive fake users.
     */
    private void startUsersAnonymous(int wait)
    {
        logger.info("Starting the Hammer : starting all "
                            + "FakeUsers with anonymous login");
        try
        {
            for(FakeUser user : fakeUsers)
            {
                FakeUserStats userStats;
                user.start();
                if(serverInfo.getFocusUserJid() != null) {
                    user.createConference(serverInfo.getFocusUserJid());
                }
                if (hammerStats != null
                        && (userStats = user.getFakeUserStats()) != null)
                    hammerStats.addFakeUsersStats(userStats);
                Thread.sleep(wait);
            }
        }
        catch (XMPPException e)
        {
            e.printStackTrace();
            System.exit(1);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }

    }
    /**
     * Start the <tt>HammerStats</tt> used by this <tt>Hammer</tt> to keep track
     * of the streams stats.
     *
     * @param overallStats enable or not the logging of the overall stats
     * computed at the end of the run.
     * @param allStats enable or not the logging of the all the stats collected
     * by the <tt>HammerStats</tt> during the run.
     * @param summaryStats enable or not the logging of the summary stats
     * computed from all the streams' stats collected by the
     * <tt>HammerStats</tt> during the run.
     * @param statsPollingTime the number of seconds between two polling of stats
     * by the <tt>HammerStats</tt> run method.
     */
    private void startStats(
        boolean overallStats,
        boolean allStats,
        boolean summaryStats,
        int statsPollingTime)
    {
        logger.info(String.format("Starting the HammerStats with "
                        + "(overall stats : %s), "
                        + "(summary stats : %s), (all stats : %s) and a polling of %dsec",
                overallStats, summaryStats, allStats, statsPollingTime));
        hammerStats.setOverallStatsLogging(overallStats);
        hammerStats.setAllStatsLogging(allStats);
        hammerStats.setSummaryStatsLogging(summaryStats);
        hammerStats.setTimeBetweenUpdate(statsPollingTime);
        hammerStatsThread.start();
    }


    /**
     * Stop the streams of all the fake users created, and disconnect them
     * from the MUC and the XMPP server.
     * Also stop the <tt>HammerStats</tt> thread.
     */
    public synchronized void stop()
    {
        if (!this.started)
        {
            logger.warn("Hammer already stopped !");
            return;
        }

        logger.info("Stopping the Hammer : stopping all FakeUser");
        for(FakeUser user : fakeUsers)
        {
            user.stop();
        }

        /*
         * Stop the thread of the HammerStats, without using the Thread
         * instance hammerStatsThread, to allow it to cleanly stop.
         */
        logger.info("Stopping the HammerStats and waiting for its thread to return");
        if (hammerStats != null)
            hammerStats.stop();
        try
        {
            if(hammerStatsThread != null)
                hammerStatsThread.join();
        }
        catch (InterruptedException e)
        {
            logger.warn("Exception while waiting for the statistics thread to end", e);
        }

        this.started = false;
        logger.info("The Hammer has been correctly stopped");
    }
}

