package com.uniquid.tank;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Properties;
import java.util.Set;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.Utils;
import org.bitcoinj.wallet.DeterministicSeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.uniquid.core.ProviderRequest;
import com.uniquid.core.connector.Connector;
import com.uniquid.core.connector.UserClient;
import com.uniquid.core.connector.mqtt.AnnouncerProviderRequest;
import com.uniquid.core.connector.mqtt.MQTTConnector;
import com.uniquid.core.connector.mqtt.MQTTUserClient;
import com.uniquid.core.impl.UniquidSimplifier;
import com.uniquid.node.UniquidNode;
import com.uniquid.node.UniquidNodeState;
import com.uniquid.node.impl.UniquidNodeImpl;
import com.uniquid.node.impl.UniquidNodeImpl.Builder;
import com.uniquid.node.listeners.UniquidNodeEventListener;
import com.uniquid.register.RegisterFactory;
import com.uniquid.register.impl.sql.SQLiteRegisterFactory;
import com.uniquid.register.provider.ProviderChannel;
import com.uniquid.register.user.UserChannel;
import com.uniquid.tank.function.InputFaucetFunction;
import com.uniquid.tank.function.OutputFaucetFunction;
import com.uniquid.tank.function.TankFunction;
import com.uniquid.utils.SeedUtils;

/*
 * Example to show how to build a Tank simulator with Uniquid Node capabilities
 */
public class Main {

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class.getName());

	private static final String APPCONFIG_PROPERTIES = "/appconfig.properties";
	
	private static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

	public static void main(String[] args) throws Exception {
		
		// Read configuration properties
		InputStream inputStream = null;
		
		if (args.length != 0) {
			
			// the first parameter is the properties file that contains application's configuration
			inputStream = new FileInputStream(new File(args[0]));
			
		} else {
			
			// if the user did not pass properties file, then we use our default one (inside the jar)
			inputStream = Main.class.getResourceAsStream(APPCONFIG_PROPERTIES);
			
		}
		
		Properties properties = new Properties();
		properties.load(inputStream);
		
		// close input stream
		inputStream.close();

		// Init settings
		final TankSettings appSettings = new TankSettings(properties);

		// Read network parameters
		NetworkParameters networkParameters = appSettings.getNetworkParameters();

		// Read provider wallet
		File providerWalletFile = appSettings.getProviderWalletFile();

		// Read wallet file
		File userWalletFile = appSettings.getUserWalletFile();
		
		// Read chain file
		File chainFile = appSettings.getChainFile();
		
		// Read chain file
		File userChainFile = appSettings.getUserChainFile();
		
		// Machine name
		String machineName = "JTank" + getRandomName();
		
		// Seed backup file
		File seedFile = appSettings.getSeedFile();
		
		//
		// 1 Create Register Factory: we choose the SQLiteRegisterFactory implementation.
		//
		RegisterFactory registerFactory = new SQLiteRegisterFactory(appSettings.getDBUrl());
		
		//
		// 2 start to construct an UniquidNode...
		//
		final UniquidNode uniquidNode;
		
		// ... if the seed file exists then we use the SeedUtils to open it and decrypt its content: we can extract the
		// mnemonic string, creationtime and name to restore the node; otherwise we create a new node initialized with a
		// random seed and then we use a SeedUtils to perform an encrypted backup of the seed and other properties
		if (seedFile.exists() && !seedFile.isDirectory()) {
			
			// create a SeedUtils (the wrapper that is able to load/read/decrypt the seed file)
			SeedUtils seedUtils = new SeedUtils(seedFile);
			
			// decrypt the content with the password read from the application setting properties
			Object[] readData = seedUtils.readData(appSettings.getSeedPassword());

			// fetch mnemonic string
			final String mnemonic = (String) readData[0];

			// fetch creation time
			final int creationTime = (Integer) readData[1];
			
			machineName = (String) readData[2];
			
			// now we build an UniquidNode with the data read from seed file: we choose the UniquidNodeImpl
			// implementation
			uniquidNode = new UniquidNodeImpl.Builder().
					setNetworkParameters(networkParameters).
					setProviderFile(providerWalletFile).
					setUserFile(userWalletFile).
					setChainFile(chainFile).
					setUserChainFile(userChainFile).
					setRegisterFactory(registerFactory).
					setNodeName(machineName).
					buildFromMnemonic(mnemonic, creationTime);
			
		} else {
		
			// We create a builder with specified settings
			Builder builder = new UniquidNodeImpl.Builder().
					setNetworkParameters(networkParameters).
					setProviderFile(providerWalletFile).
					setUserFile(userWalletFile).
					setChainFile(chainFile).
					setUserChainFile(userChainFile).
					setRegisterFactory(registerFactory).
					setNodeName(machineName);
			
			// ask the builder to create a node with a random seed
			uniquidNode = builder.build();
			
			// Now we fetch from the builder the DeterministicSeed that allow us to export mnemonics and creationtime
			DeterministicSeed seed = builder.getDeterministicSeed();
			
			// we save the creation time
			long creationTime = seed.getCreationTimeSeconds();
			
			// we save mnemonics
			String mnemonics = Utils.join(seed.getMnemonicCode());
			
			// we prepare the data to save for seedUtils
			Object[] saveData = new Object[] {mnemonics, creationTime, machineName};
			
			// we construct a seedutils
			SeedUtils seedUtils = new SeedUtils(seedFile);
			
			// now backup mnemonics encrypted on disk
			seedUtils.saveData(saveData, appSettings.getSeedPassword());
		
		}
		
		//
		// 2 ...we finished to build an UniquidNode
		// 
		
		// Here we register a callback on the uniquidNode that allow us to be triggered when some interesting events happens
		// Currently we are only interested in receiving the onNodeStateChange() event. The other methods are present
		// because we decided to use an anonymous inner class.
		uniquidNode.addUniquidNodeEventListener(new UniquidNodeEventListener() {
			
			@Override
			public void onUserContractRevoked(UserChannel arg0) {
				// NOTHING TO DO
			}
			
			@Override
			public void onUserContractCreated(UserChannel arg0) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onSyncStarted(int arg0) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onSyncProgress(double arg0, int arg1, Date arg2) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onSyncNodeStart() {
				// NOTHING TO DO				
			}
			
			@Override
			public void onSyncNodeEnd() {
				// NOTHING TO DO				
			}
			
			@Override
			public void onSyncEnded() {
				// NOTHING TO DO				
			}
			
			@Override
			public void onProviderContractRevoked(ProviderChannel arg0) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onProviderContractCreated(ProviderChannel arg0) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onPeersDiscovered(Set<PeerAddress> arg0) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onPeerDisconnected(Peer arg0, int arg1) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onPeerConnected(Peer arg0, int arg1) {
				// NOTHING TO DO				
			}
			
			@Override
			public void onNodeStateChange(UniquidNodeState arg0) {

				// Register an handler that allow to send an imprinting message to the imprinter
				try {

					// If the node is ready to be imprinted...
					if (UniquidNodeState.IMPRINTING.equals(arg0)) {

						// Create a MQTTClient pointing to the broker on the UID/announce topic and specify
						// 0 timeout: we don't want a response.
						final UserClient rpcClient = new MQTTUserClient(appSettings.getMQTTBroker(), "UID/announce", 0);
						
						// Create announce request
						final ProviderRequest providerRequest = new AnnouncerProviderRequest.Builder()
								.set_sender(uniquidNode.getNodeName())
								.set_name(uniquidNode.getNodeName())
								.set_xpub(uniquidNode.getPublicKey())
								.build();
						
						// send the request.  The server will not reply (but will do an imprint on blockchain) so
						// the timeout exception here is expected
						rpcClient.sendOutputMessage(providerRequest);
						
					}

				} catch (Exception ex) {
					// expected! the server will not reply
				}
			}

		});
		
		//
		// 3 Create connector: we choose the MQTTConnector implementation
		//
		final Connector mqttProviderConnector = new MQTTConnector.Builder()
				.set_broker(appSettings.getMQTTBroker())
				.set_topic(machineName)
				.build();
		
		// 
		// 4 Create UniquidSimplifier that wraps registerFactory, connector and uniquidnode
		final UniquidSimplifier simplifier = new UniquidSimplifier(registerFactory, mqttProviderConnector, uniquidNode);
		
		// 5 Register custom functions on slot 34, 35, 36
		simplifier.addFunction(new TankFunction(), 34);
		simplifier.addFunction(new InputFaucetFunction(), 35);
		simplifier.addFunction(new OutputFaucetFunction(), 36);
		
		LOGGER.info("Staring Uniquid library with node: " + machineName);
		
		//
		// 6 start Uniquid core library: this will init the node, sync on blockchain, and use the provided
		// registerFactory to interact with the persistence layer
		simplifier.start();
		
		// Register shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			public void run() {

				LOGGER.info("Terminating tank");
				try {

					// tell the library to shutdown and close all opened resources
					simplifier.shutdown();

				} catch (Exception ex) {

					LOGGER.error("Exception while terminating tank", ex);

				}
			}
		});
		
		LOGGER.info("Exiting");
		
	}
	
	public static String getRandomName() {
		
		SecureRandom random = new SecureRandom();
		
		int len = 12;

		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(AB.charAt(random.nextInt(AB.length())));
		}
		
		return sb.toString();

	}

}
