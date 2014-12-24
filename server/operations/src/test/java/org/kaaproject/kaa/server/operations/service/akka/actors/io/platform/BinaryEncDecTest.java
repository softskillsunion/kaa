package org.kaaproject.kaa.server.operations.service.akka.actors.io.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kaaproject.kaa.server.operations.pojo.Base64Util;
import org.kaaproject.kaa.server.operations.pojo.sync.ClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ConfigurationClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.Event;
import org.kaaproject.kaa.server.operations.pojo.sync.EventClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.EventSequenceNumberResponse;
import org.kaaproject.kaa.server.operations.pojo.sync.EventServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.LogClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.LogServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.NotificationClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ProfileClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ProfileServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.SubscriptionCommandType;
import org.kaaproject.kaa.server.operations.pojo.sync.SyncResponseStatus;
import org.kaaproject.kaa.server.operations.pojo.sync.SyncStatus;
import org.kaaproject.kaa.server.operations.pojo.sync.UserAttachNotification;
import org.kaaproject.kaa.server.operations.pojo.sync.UserClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.UserServerSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinaryEncDecTest {

    private static final Logger LOG = LoggerFactory.getLogger(BinaryEncDecTest.class);
    
    private static final int SHA_1_LENGTH = 20;
    private static final int MAGIC_NUMBER = 42;
    private static final int MAGIC_INDEX = 3;
    private static final short BIG_MAGIC_NUMBER = (short) (MAGIC_NUMBER * MAGIC_NUMBER);
    private BinaryEncDec encDec;

    @Before
    public void before() {
        encDec = new BinaryEncDec();
    }

    @Test(expected = PlatformEncDecException.class)
    public void testSmall() throws PlatformEncDecException {
        encDec.decode("small".getBytes());
    }

    @Test(expected = PlatformEncDecException.class)
    public void testWrongProtocol() throws PlatformEncDecException {
        encDec.decode(buildHeader(Integer.MAX_VALUE, 0, 0));
    }

    @Test(expected = PlatformEncDecException.class)
    public void testToOldVersion() throws PlatformEncDecException {
        encDec.decode(buildHeader(BinaryEncDec.PROTOCOL_ID, BinaryEncDec.MIN_SUPPORTED_VERSION - 1, 0));
    }

    @Test(expected = PlatformEncDecException.class)
    public void testVeryNewVersion() throws PlatformEncDecException {
        encDec.decode(buildHeader(BinaryEncDec.PROTOCOL_ID, BinaryEncDec.MAX_SUPPORTED_VERSION + 1, 0));
    }

    @Test(expected = PlatformEncDecException.class)
    public void testNoMetaData() throws PlatformEncDecException {
        encDec.decode(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 0));
    }

    @Test(expected = PlatformEncDecException.class)
    public void testSmallExtensionHeaderData() throws PlatformEncDecException {
        encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 1), "trash".getBytes()));
    }

    @Test(expected = PlatformEncDecException.class)
    public void testWrongPayloadLengthData() throws PlatformEncDecException {
        encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 1),
                buildExtensionHeader(BinaryEncDec.META_DATA_EXTENSION_ID, 0, 0, 0, 200)));
    }

    @Test
    public void testParseMetaDataWithNoOptions() throws PlatformEncDecException {
        byte[] md = new byte[4];
        md[0] = 1;
        md[1] = 2;
        md[2] = 3;
        md[3] = 4;
        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 1),
                buildExtensionHeader(BinaryEncDec.META_DATA_EXTENSION_ID, 0, 0, 0, md.length), md));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertEquals(1 * 256 * 256 * 256 + 2 * 256 * 256 + 3 * 256 + 4, sync.getRequestId().intValue());
    }

    @Test
    public void testParseMetaDataWithOptions() throws PlatformEncDecException {
        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 1), getValidMetaData()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertEquals(1, sync.getRequestId().intValue());
        Assert.assertEquals(60l, sync.getClientSyncMetaData().getTimeout());
        Assert.assertEquals(MAGIC_NUMBER, sync.getClientSyncMetaData().getEndpointPublicKeyHash().get(MAGIC_INDEX));
        Assert.assertEquals(MAGIC_NUMBER + 1, sync.getClientSyncMetaData().getProfileHash().get(MAGIC_INDEX));
    }

    @Test
    public void testEncodeBasicServerSync() throws PlatformEncDecException {
        ServerSync sync = new ServerSync();
        sync.setRequestId(MAGIC_NUMBER);

        ByteBuffer buf = ByteBuffer.wrap(encDec.encode(sync));
        int size = 8 // header
        + 8 + 4 // metadata
        ;
        Assert.assertEquals(size, buf.array().length);
        buf.position(buf.capacity() - 4);
        Assert.assertEquals(MAGIC_NUMBER, buf.getInt());
        LOG.trace(Arrays.toString(buf.array()));
    }

    @Test
    public void testEncodeProfileServerSync() throws PlatformEncDecException {
        ServerSync sync = new ServerSync();
        sync.setRequestId(MAGIC_NUMBER);
        ProfileServerSync pSync = new ProfileServerSync(SyncResponseStatus.RESYNC);
        sync.setProfileSync(pSync);

        ByteBuffer buf = ByteBuffer.wrap(encDec.encode(sync));
        int size = 8 // header
        + 8 + 4 // metadata
        + 8 // profile sync
        ;
        Assert.assertEquals(size, buf.array().length);
        buf.position(buf.capacity() - 12);
        Assert.assertEquals(MAGIC_NUMBER, buf.getInt());
        LOG.trace(Arrays.toString(buf.array()));
    }

    @Test
    public void testEncodeLogServerSync() throws PlatformEncDecException {
        ServerSync sync = new ServerSync();
        sync.setRequestId(MAGIC_NUMBER);
        LogServerSync lSync = new LogServerSync("" + MAGIC_NUMBER, SyncStatus.FAILURE);
        sync.setLogSync(lSync);

        ByteBuffer buf = ByteBuffer.wrap(encDec.encode(sync));
        int size = 8 // header
                + 8 + 4 // metadata
                + 8 + 4// log sync
        ;
        Assert.assertEquals(size, buf.array().length);
        buf.position(buf.capacity() - 4);
        Assert.assertEquals(MAGIC_NUMBER, buf.getShort());
        buf.position(buf.capacity() - 2);
        Assert.assertEquals(BinaryEncDec.FAILURE, buf.get());
        LOG.trace(Arrays.toString(buf.array()));
    }

    @Test
    public void testEncodeUserServerSync() throws PlatformEncDecException {
        ServerSync sync = new ServerSync();
        sync.setRequestId(MAGIC_NUMBER);
        UserServerSync uSync = new UserServerSync();
        uSync.setUserAttachNotification(new UserAttachNotification("id", "token"));
        sync.setUserSync(uSync);

        ByteBuffer buf = ByteBuffer.wrap(encDec.encode(sync));
        int size = 8 // header
                + 8 + 4 // metadata
                + 8 + 4 + 4 + 8// user sync
        ;
        Assert.assertEquals(size, buf.array().length);
        LOG.trace(Arrays.toString(buf.array()));
    }
    
    @Test
    public void testEncodeEventServerSync() throws PlatformEncDecException {
        ServerSync sync = new ServerSync();
        sync.setRequestId(MAGIC_NUMBER);
        EventServerSync eSync = new EventServerSync();
        eSync.setEventSequenceNumberResponse(new EventSequenceNumberResponse(MAGIC_NUMBER));
        Event event = new Event();
        event.setEventClassFQN("fqn");
        event.setSource(Base64Util.encode(new byte[SHA_1_LENGTH]));
        eSync.setEvents(Collections.singletonList(event));
        sync.setEventSync(eSync);

        ByteBuffer buf = ByteBuffer.wrap(encDec.encode(sync));
        int size = 8 // header
                + 8 + 4 // metadata
                + 8 + 4 // event header + seq number 
                + 4 + 4 + SHA_1_LENGTH + 4// event sync
        ;
        System.out.println(Arrays.toString(buf.array()));
        Assert.assertEquals(size, buf.array().length);
        
    }

    private byte[] getValidMetaData() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[8 + SHA_1_LENGTH + SHA_1_LENGTH + 20]);
        buf.putInt(1);
        buf.putInt(60);
        byte[] keyHash = new byte[SHA_1_LENGTH];
        keyHash[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(keyHash);
        byte[] profileHash = new byte[SHA_1_LENGTH];
        profileHash[MAGIC_INDEX] = MAGIC_NUMBER + 1;
        buf.put(profileHash);
        buf.put("12345678900987654321".getBytes(Charset.forName("UTF-8")));
        return concat(buildExtensionHeader(BinaryEncDec.META_DATA_EXTENSION_ID, 0, 0, 0x0F, buf.array().length), buf.array());        
    }

    @Test
    public void testProfileClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + 100 + (4 * 5) + 12 + 4 + 128 + 4 + 8]);
        // profile length and data
        buf.putInt(100);
        byte[] profileBody = new byte[100];
        profileBody[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(profileBody);
        // profile schema
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (MAGIC_NUMBER + 1));
        // config schema
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (MAGIC_NUMBER + 2));
        // system nf schema
        buf.put((byte) 2);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (MAGIC_NUMBER + 3));
        // user nf schema
        buf.put((byte) 3);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (MAGIC_NUMBER + 4));
        // log schema
        buf.put((byte) 4);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) (MAGIC_NUMBER + 5));
        // event family count
        buf.put((byte) 5);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        // event family version and length
        buf.putShort((short) MAGIC_NUMBER);
        buf.putShort((short) 4);
        buf.put("name".getBytes(Charset.forName("UTF-8")));
        // public key
        buf.put((byte) 6);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 128);
        byte[] keyBody = new byte[128];
        keyBody[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(keyBody);
        // access token
        buf.put((byte) 7);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 5);
        buf.put("token".getBytes(Charset.forName("UTF-8")));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.PROFILE_EXTENSION_ID, 0, 0, 0, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getProfileSync());
        ProfileClientSync pSync = sync.getProfileSync();
        Assert.assertEquals(MAGIC_NUMBER, pSync.getProfileBody().array()[MAGIC_INDEX]);
        Assert.assertEquals(MAGIC_NUMBER, pSync.getEndpointPublicKey().array()[MAGIC_INDEX]);
        Assert.assertNotNull(pSync.getVersionInfo());
        Assert.assertEquals(MAGIC_NUMBER + 1, pSync.getVersionInfo().getProfileVersion());
        Assert.assertEquals(MAGIC_NUMBER + 2, pSync.getVersionInfo().getConfigVersion());
        Assert.assertEquals(MAGIC_NUMBER + 3, pSync.getVersionInfo().getSystemNfVersion());
        Assert.assertEquals(MAGIC_NUMBER + 4, pSync.getVersionInfo().getUserNfVersion());
        Assert.assertEquals(MAGIC_NUMBER + 5, pSync.getVersionInfo().getLogSchemaVersion());
        Assert.assertEquals(1, pSync.getVersionInfo().getEventFamilyVersions().size());
        Assert.assertEquals("name", pSync.getVersionInfo().getEventFamilyVersions().get(0).getName());
        Assert.assertEquals(MAGIC_NUMBER, pSync.getVersionInfo().getEventFamilyVersions().get(0).getVersion());
        Assert.assertEquals("token", pSync.getEndpointAccessToken());
    }

    @Test
    public void testUserClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + 4 + 8 + 4 + 4 + 8 + 4 + 4 + SHA_1_LENGTH]);
        // user assign request
        buf.put((byte) 0);
        buf.put((byte) 4);
        buf.put((byte) 0);
        buf.put((byte) 5);
        buf.put("user".getBytes(Charset.forName("UTF-8")));
        buf.put("token".getBytes(Charset.forName("UTF-8")));
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        // attach requests
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putShort(BIG_MAGIC_NUMBER);
        buf.put((byte) 0);
        buf.put((byte) 6);
        buf.put("token2".getBytes(Charset.forName("UTF-8")));
        buf.put((byte) 0);
        buf.put((byte) 0);
        // detach requests
        buf.put((byte) 2);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putShort((short) (BIG_MAGIC_NUMBER + 1));
        buf.put((byte) 0);
        buf.put((byte) 0);
        byte[] keyHash = new byte[SHA_1_LENGTH];
        keyHash[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(keyHash);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.USER_EXTENSION_ID, 0, 0, 0, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getUserSync());
        UserClientSync uSync = sync.getUserSync();
        Assert.assertNotNull(uSync.getUserAttachRequest());
        Assert.assertEquals("user", uSync.getUserAttachRequest().getUserExternalId());
        Assert.assertEquals("token", uSync.getUserAttachRequest().getUserAccessToken());
        Assert.assertNotNull(uSync.getEndpointAttachRequests());
        Assert.assertEquals(1, uSync.getEndpointAttachRequests().size());
        Assert.assertEquals(BIG_MAGIC_NUMBER + "", uSync.getEndpointAttachRequests().get(0).getRequestId());
        Assert.assertEquals("token2", uSync.getEndpointAttachRequests().get(0).getEndpointAccessToken());
        Assert.assertNotNull(uSync.getEndpointDetachRequests());
        Assert.assertEquals(1, uSync.getEndpointDetachRequests().size());
        Assert.assertEquals((BIG_MAGIC_NUMBER + 1) + "", uSync.getEndpointDetachRequests().get(0).getRequestId());
        Assert.assertEquals(Base64Util.encode(keyHash), uSync.getEndpointDetachRequests().get(0).getEndpointKeyHash());
    }

    @Test
    public void testLogClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + 4 + 128]);
        // user assign request
        buf.putShort(BIG_MAGIC_NUMBER);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putInt(127);
        byte[] logData = new byte[127];
        logData[MAGIC_NUMBER] = MAGIC_NUMBER;
        buf.put(logData);
        buf.put((byte) 0);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.LOGGING_EXTENSION_ID, 0, 0, 0, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getLogSync());
        LogClientSync logSync = sync.getLogSync();
        Assert.assertEquals(BIG_MAGIC_NUMBER + "", logSync.getRequestId());
        Assert.assertNotNull(logSync.getLogEntries());
        Assert.assertEquals(1, logSync.getLogEntries().size());
        Assert.assertEquals(MAGIC_NUMBER, logSync.getLogEntries().get(0).getData().array()[MAGIC_NUMBER]);
    }

    @Test
    public void testConfigurationClientSyncWithEmptyHash() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4]);
        // user assign request
        buf.putInt(MAGIC_NUMBER);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.CONFIGURATION_EXTENSION_ID, 0, 0, 0, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getConfigurationSync());
        ConfigurationClientSync cSync = sync.getConfigurationSync();
        Assert.assertEquals(MAGIC_NUMBER, cSync.getAppStateSeqNumber());
        Assert.assertNull(cSync.getConfigurationHash());
    }

    @Test
    public void testConfigurationClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + SHA_1_LENGTH]);
        // user assign request
        buf.putInt(MAGIC_NUMBER);
        byte[] hash = new byte[SHA_1_LENGTH];
        hash[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(hash);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.CONFIGURATION_EXTENSION_ID, 0, 0, 0x02, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getConfigurationSync());
        ConfigurationClientSync cSync = sync.getConfigurationSync();
        Assert.assertEquals(MAGIC_NUMBER, cSync.getAppStateSeqNumber());
        Assert.assertEquals(MAGIC_NUMBER, cSync.getConfigurationHash().array()[MAGIC_INDEX]);
    }

    @Test
    public void testNotificationClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + // seq number
                4 + 8 + 4 + // topic list
                4 + 4 + 3 + 1 + // unicast notifications
                4 + 8 + // add topic command
                4 + 8 + // remove topic command
                SHA_1_LENGTH]); // topic list hash
        // seq number
        buf.putInt(MAGIC_NUMBER);
        // topic list
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putLong(303l);
        buf.putInt(MAGIC_NUMBER);
        // unicast notifications
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putInt(3);
        buf.put("uid".getBytes(Charset.forName("UTF-8")));
        buf.put((byte) 0);
        // add topic command
        buf.put((byte) 2);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putLong(101);
        // remove topic command
        buf.put((byte) 3);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        buf.putLong(202);

        byte[] hash = new byte[SHA_1_LENGTH];
        hash[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(hash);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.NOTIFICATION_EXTENSION_ID, 0, 0, 0x02, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getNotificationSync());
        NotificationClientSync nSync = sync.getNotificationSync();
        Assert.assertEquals(MAGIC_NUMBER, nSync.getAppStateSeqNumber());
        Assert.assertEquals(MAGIC_NUMBER, nSync.getTopicListHash().array()[MAGIC_INDEX]);
        Assert.assertNotNull(nSync.getAcceptedUnicastNotifications());
        Assert.assertEquals(1, nSync.getAcceptedUnicastNotifications().size());
        Assert.assertEquals("uid", nSync.getAcceptedUnicastNotifications().get(0));
        Assert.assertNotNull(nSync.getSubscriptionCommands());
        Assert.assertEquals(2, nSync.getSubscriptionCommands().size());
        Assert.assertEquals(SubscriptionCommandType.ADD, nSync.getSubscriptionCommands().get(0).getCommand());
        Assert.assertEquals("101", nSync.getSubscriptionCommands().get(0).getTopicId());
        Assert.assertEquals(SubscriptionCommandType.REMOVE, nSync.getSubscriptionCommands().get(1).getCommand());
        Assert.assertEquals("202", nSync.getSubscriptionCommands().get(1).getTopicId());
        Assert.assertNotNull(nSync.getTopicStates());
        Assert.assertEquals(1, nSync.getTopicStates().size());
        Assert.assertEquals("303", nSync.getTopicStates().get(0).getTopicId());
        Assert.assertEquals(MAGIC_NUMBER, nSync.getTopicStates().get(0).getSeqNumber());
        Assert.assertEquals(MAGIC_NUMBER, nSync.getTopicListHash().array()[MAGIC_INDEX]);
    }

    @Test
    public void testEventClientSync() throws PlatformEncDecException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[4 + // listeners
                2 + 2 + 2 + 2 + 4 + // listener
                4 + // events
                4 + 2 + 2 + 4 + 4 + SHA_1_LENGTH + 100]); // event
        // listeners
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        // listener
        buf.putShort((short) MAGIC_NUMBER);
        buf.putShort((short) 1);
        buf.putShort((short) 4);
        buf.putShort((short) 0);
        buf.put("name".getBytes(Charset.forName("UTF-8")));
        // events
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 1);
        // event
        buf.putInt(MAGIC_NUMBER);
        buf.putShort((short) 0x03);
        buf.putShort((short) 4);
        buf.putInt(100);
        byte[] hash = new byte[SHA_1_LENGTH];
        hash[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(hash);

        buf.put("name".getBytes(Charset.forName("UTF-8")));

        byte[] data = new byte[100];
        data[MAGIC_INDEX] = MAGIC_NUMBER;
        buf.put(data);

        ClientSync sync = encDec.decode(concat(buildHeader(BinaryEncDec.PROTOCOL_ID, 1, 2), getValidMetaData(),
                buildExtensionHeader(BinaryEncDec.EVENT_EXTENSION_ID, 0, 0, 0x02, buf.array().length), buf.array()));
        Assert.assertNotNull(sync);
        Assert.assertNotNull(sync.getClientSyncMetaData());
        Assert.assertNotNull(sync.getEventSync());
        EventClientSync eSync = sync.getEventSync();
        Assert.assertEquals(true, eSync.isSeqNumberRequest());
        Assert.assertNotNull(eSync.getEventListenersRequests());
        Assert.assertEquals(1, eSync.getEventListenersRequests().size());
        Assert.assertEquals(MAGIC_NUMBER + "", eSync.getEventListenersRequests().get(0).getRequestId());
        Assert.assertNotNull(eSync.getEventListenersRequests().get(0).getEventClassFQNs());
        Assert.assertEquals("name", eSync.getEventListenersRequests().get(0).getEventClassFQNs().get(0));
        Assert.assertNotNull(eSync.getEvents());
        Assert.assertEquals(1, eSync.getEvents().size());
        Assert.assertEquals(MAGIC_NUMBER, eSync.getEvents().get(0).getSeqNum());
        Assert.assertEquals("name", eSync.getEvents().get(0).getEventClassFQN());
        Assert.assertEquals(Base64Util.encode(hash), eSync.getEvents().get(0).getTarget());
        Assert.assertEquals(MAGIC_NUMBER, eSync.getEvents().get(0).getEventData().array()[MAGIC_INDEX]);
    }

    public byte[] buildHeader(int protocolId, int protocolVersion, int extensionsCount) {
        ByteBuffer buf = ByteBuffer.wrap(new byte[8]);
        buf.putInt(protocolId);
        buf.putShort((short) protocolVersion);
        buf.putShort((short) extensionsCount);
        return buf.array();
    }

    private byte[] buildExtensionHeader(int type, int optionsA, int optionsB, int optionsC, int length) {
        ByteBuffer buf = ByteBuffer.wrap(new byte[8]);
        buf.put((byte) type);
        buf.put((byte) optionsA);
        buf.put((byte) optionsB);
        buf.put((byte) optionsC);
        buf.putInt(length);
        return buf.array();
    }

    public byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            try {
                outputStream.write(array);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        return outputStream.toByteArray();
    }
}
