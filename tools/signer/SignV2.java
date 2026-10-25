import com.android.signapk.ApkSignerV2;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Usage: SignV2 in.apk out.apk keystore.p12 storepass alias */
public class SignV2 {
    public static void main(String[] a) throws Exception {
        byte[] in = Files.readAllBytes(Paths.get(a[0]));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new FileInputStream(a[2])) { ks.load(is, a[3].toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey(a[4], a[3].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[4]);
        ApkSignerV2.SignerConfig cfg = new ApkSignerV2.SignerConfig();
        cfg.privateKey = key;
        cfg.certificates = Collections.singletonList(cert);
        cfg.signatureAlgorithms = Collections.singletonList(ApkSignerV2.SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256);
        ByteBuffer[] out = ApkSignerV2.sign(ByteBuffer.wrap(in), Collections.singletonList(cfg));
        try (FileOutputStream fos = new FileOutputStream(a[1]); FileChannel ch = fos.getChannel()) {
            for (ByteBuffer b : out) { while (b.hasRemaining()) ch.write(b); }
        }
        System.out.println("signed (v2): " + a[1]);
    }
}
