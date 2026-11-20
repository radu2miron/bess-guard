package edu.tucn.bessguard.service;

import edu.tucn.bessguard.exceptions.BlockchainRelatedException;
import edu.tucn.bessguard.model.TransactionRequest;
import edu.tucn.bessguard.model.TxType;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PostConstruct;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.identity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 * Fabric Gateway integration adapted from the original BlockchainProxy.
 */
@Component
public final class BlockchainProxy {

    @Value("${blockchain.msp-id}")
    private String configuredMspId;

    @Value("${blockchain.channel-name}")
    private String configuredChannelName;

    @Value("${blockchain.chaincode-name}")
    private String configuredChaincodeName;

    @Value("${blockchain.crypto-path}")
    private String configuredCryptoPath;

    @Value("${blockchain.cert-path}")
    private String configuredCertPath;

    @Value("${blockchain.key-dir-path}")
    private String configuredKeyDirPath;

    @Value("${blockchain.tls-cert-path}")
    private String configuredTlsCertPath;

    @Value("${blockchain.peer-endpoint}")
    private String peerEndpoint;

    @Value("${blockchain.override_authority}")
    private String overrideAuthority;

    private String mspId;
    private String channelName;
    private String chaincodeName;
    private Path certPath;
    private Path keyDirPath;
    private Path tlsCertPath;

    @PostConstruct
    public void init() {
        mspId = System.getenv().getOrDefault("MSP_ID", configuredMspId);
        channelName = System.getenv().getOrDefault("CHANNEL_NAME", configuredChannelName);
        chaincodeName = System.getenv().getOrDefault("CHAINCODE_NAME", configuredChaincodeName);
        Path cryptoPath = Paths.get(configuredCryptoPath).toAbsolutePath().normalize();
        certPath = cryptoPath.resolve(configuredCertPath);
        keyDirPath = cryptoPath.resolve(configuredKeyDirPath);
        tlsCertPath = cryptoPath.resolve(configuredTlsCertPath);
    }

    public String execute(TransactionRequest request) {
        ManagedChannel channel = null;

        try {
            channel = getNewChannel();

            try (Gateway gateway = getGateway(channel)) {
                Contract contract = gateway.getNetwork(channelName).getContract(chaincodeName);
                String[] args = request.getArgs() == null ? new String[0] : request.getArgs();
                byte[] result;

                if (TxType.READ == request.getTxType()) {
                    result = contract.evaluateTransaction(request.getFunction(), args);
                } else {
                    // submitTransaction waits for a VALID commit or raises a commit status exception.
                    result = contract.submitTransaction(request.getFunction(), args);
                }

                return new String(result, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new BlockchainRelatedException("Fabric transaction " + request.getFunction()
                    + " failed: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.shutdownNow();

                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public Gateway getGateway(Channel channel) throws CertificateException, IOException, InvalidKeyException {
        return Gateway.newInstance()
                .identity(newIdentity())
                .signer(newSigner())
                .connection(channel)
                .evaluateOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                .endorseOptions(options -> options.withDeadlineAfter(15, TimeUnit.SECONDS))
                .submitOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                .commitStatusOptions(options -> options.withDeadlineAfter(1, TimeUnit.MINUTES))
                .connect();
    }

    public ManagedChannel getNewChannel() throws IOException {
        var credentials = TlsChannelCredentials.newBuilder().trustManager(tlsCertPath.toFile()).build();
        return Grpc.newChannelBuilder(peerEndpoint, credentials)
                .overrideAuthority(overrideAuthority)
                .build();
    }

    private Identity newIdentity() throws IOException, CertificateException {
        try (var certificateReader = Files.newBufferedReader(certPath)) {
            return new X509Identity(mspId, Identities.readX509Certificate(certificateReader));
        }
    }

    private Signer newSigner() throws IOException, InvalidKeyException {
        try (var keyReader = Files.newBufferedReader(getPrivateKeyPath())) {
            return Signers.newPrivateKeySigner(Identities.readPrivateKey(keyReader));
        }
    }

    private Path getPrivateKeyPath() throws IOException {
        try (var keyFiles = Files.list(keyDirPath)) {
            return keyFiles.filter(Files::isRegularFile).sorted().findFirst()
                    .orElseThrow(() -> new IOException("No Fabric private key found in " + keyDirPath));
        }
    }
}
