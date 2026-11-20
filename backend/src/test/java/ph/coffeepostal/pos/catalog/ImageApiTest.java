package ph.coffeepostal.pos.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageApiTest {
    private static byte[] pad(int... head) {
        byte[] b = new byte[32];
        for (int i = 0; i < head.length; i++) b[i] = (byte) head[i];
        return b;
    }

    @Test
    void acceptsFilesThatStartLikeTheirType() {
        assertTrue(ImageApi.looksLike("image/jpeg", pad(0xFF, 0xD8, 0xFF, 0xE0)));
        assertTrue(ImageApi.looksLike("image/png", pad(0x89, 'P', 'N', 'G')));
        assertTrue(ImageApi.looksLike("image/webp", pad('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    @Test
    void rejectsRenamedOrOtherFiles() {
        assertFalse(ImageApi.looksLike("image/jpeg", pad('%', 'P', 'D', 'F')));
        assertFalse(ImageApi.looksLike("image/png", pad(0xFF, 0xD8, 0xFF, 0xE0)));
        assertFalse(ImageApi.looksLike("image/svg+xml", pad('<', 's', 'v', 'g')));
        assertFalse(ImageApi.looksLike("image/jpeg", new byte[4]));
    }
}
