package ai.reviewforge.github.client;

import ai.reviewforge.common.api.ApiException;
import ai.reviewforge.config.GitHubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
public class GitHubAppJwtFactory {

    private static final byte[] RSA_ALGORITHM_IDENTIFIER = new byte[]{
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private final GitHubProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GitHubAppJwtFactory(GitHubProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String create() {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_NOT_CONFIGURED",
                    "GitHub App credentials have not been configured on the server.");
        }

        Instant now = clock.instant();
        try {
            String header = encodeJson(Map.of("alg", "RS256", "typ", "JWT"));
            String payload = encodeJson(Map.of(
                    "iat", now.minusSeconds(60).getEpochSecond(),
                    "exp", now.plusSeconds(9 * 60).getEpochSecond(),
                    "iss", properties.appId()
            ));
            String signingInput = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(readPrivateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException | JsonProcessingException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_PRIVATE_KEY_INVALID",
                    "The configured GitHub App private key is invalid.");
        }
    }

    private String encodeJson(Map<String, ?> value) throws JsonProcessingException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private PrivateKey readPrivateKey() throws GeneralSecurityException {
        String pem = new String(Base64.getDecoder().decode(properties.privateKeyBase64()), StandardCharsets.US_ASCII)
                .replace("\r", "")
                .trim();
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String body = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(body);
        byte[] pkcs8 = pkcs1 ? wrapPkcs1InPkcs8(keyBytes) : keyBytes;
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] privateKey = der(0x04, pkcs1);
        return der(0x30, concatenate(version, RSA_ALGORITHM_IDENTIFIER, privateKey));
    }

    private static byte[] der(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] result = new byte[1 + length.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(content, 0, result, 1 + length.length, content.length);
        return result;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        int bytes = 0;
        int value = length;
        while (value > 0) {
            bytes++;
            value >>= 8;
        }
        byte[] encoded = new byte[bytes + 1];
        encoded[0] = (byte) (0x80 | bytes);
        for (int index = bytes; index > 0; index--) {
            encoded[index] = (byte) length;
            length >>= 8;
        }
        return encoded;
    }

    private static byte[] concatenate(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
