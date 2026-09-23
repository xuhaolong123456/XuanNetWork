package com.networkdisk.auth;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** 使用 JDK 自绘四位数字验证码，不引入第三方图片验证码依赖。 */
@Component
public class CaptchaGenerator {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 44;
    private static final String DIGITS = "23456789";

    private final SecureRandom random = new SecureRandom();

    public Captcha generate() {
        String code = randomCode();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(248, 251, 250));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);

            for (int i = 0; i < 7; i++) {
                graphics.setColor(new Color(120 + random.nextInt(80), 170 + random.nextInt(60), 160 + random.nextInt(70)));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
            for (int i = 0; i < code.length(); i++) {
                graphics.setColor(new Color(35 + random.nextInt(70), 75 + random.nextInt(80), 70 + random.nextInt(70)));
                graphics.drawString(String.valueOf(code.charAt(i)), 12 + i * 27, 31 + random.nextInt(4));
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new Captcha(code, "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成图片验证码", exception);
        }
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) code.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        return code.toString();
    }

    public record Captcha(String code, String image) {}
}
