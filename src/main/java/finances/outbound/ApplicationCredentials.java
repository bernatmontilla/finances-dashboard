package finances.outbound;

import finances.data.DataFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * The Enable Banking application of this installation: its id and its private key, two files in the
 * data folder's {@code secrets/} folder that the installer put there (docs/install.md). They are read
 * when needed and never shown, logged or put into a backup. A key from {@code openssl genrsa} (PKCS#1,
 * "BEGIN RSA PRIVATE KEY") and one in PKCS#8 ("BEGIN PRIVATE KEY") are both accepted.
 */
@Component
public class ApplicationCredentials {

    public static final String SECRETS_FOLDER = "secrets";
    public static final String APPLICATION_ID_FILE = "enable-banking-application-id";
    public static final String KEY_FILE = "enable-banking-key.pem";

    private final Path folder;

    public ApplicationCredentials(DataFolder dataFolder) {
        this.folder = dataFolder.path().resolve(SECRETS_FOLDER);
    }

    /** Whether both files are there. It does not say whether the key is a usable one: signing does. */
    public boolean configured() {
        return Files.isRegularFile(folder.resolve(APPLICATION_ID_FILE)) && Files.isRegularFile(folder.resolve(KEY_FILE));
    }

    String applicationId() {
        try {
            String id = Files.readString(folder.resolve(APPLICATION_ID_FILE), StandardCharsets.UTF_8).strip();
            if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]{1,100}")) {
                throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, null, 0);
            }
            return id;
        } catch (IOException e) {
            throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, e);
        }
    }

    PrivateKey privateKey() {
        try {
            String pem = Files.readString(folder.resolve(KEY_FILE), StandardCharsets.UTF_8);
            return parse(pem);
        } catch (IOException | RuntimeException e) {
            if (e instanceof BankApiException known) {
                throw known;
            }
            // The cause is dropped on purpose: its message could quote part of the file.
            throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, null, 0);
        }
    }

    static PrivateKey parse(String pem) {
        try {
            boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
            if (!pkcs1 && !pem.contains("BEGIN PRIVATE KEY")) {
                throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, null, 0);
            }
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? wrapPkcs1(der) : der));
        } catch (BankApiException e) {
            throw e;
        } catch (Exception e) {
            throw new BankApiException(BankApiException.Reason.NOT_CONFIGURED, null, 0);
        }
    }

    /** A PKCS#1 RSA key inside the PKCS#8 envelope the JDK reads: version 0, the RSA algorithm, the key as an octet string. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octets = der(0x04, pkcs1);
        byte[] content = new byte[version.length + rsaAlgorithm.length + octets.length];
        System.arraycopy(version, 0, content, 0, version.length);
        System.arraycopy(rsaAlgorithm, 0, content, version.length, rsaAlgorithm.length);
        System.arraycopy(octets, 0, content, version.length + rsaAlgorithm.length, octets.length);
        return der(0x30, content);
    }

    private static byte[] der(int tag, byte[] content) {
        int length = content.length;
        byte[] header;
        if (length < 0x80) {
            header = new byte[] {(byte) tag, (byte) length};
        } else if (length < 0x100) {
            header = new byte[] {(byte) tag, (byte) 0x81, (byte) length};
        } else if (length < 0x10000) {
            header = new byte[] {(byte) tag, (byte) 0x82, (byte) (length >> 8), (byte) length};
        } else {
            header = new byte[] {(byte) tag, (byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
        }
        byte[] all = new byte[header.length + length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(content, 0, all, header.length, length);
        return all;
    }
}
