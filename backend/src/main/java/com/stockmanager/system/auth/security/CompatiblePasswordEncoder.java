// 声明密码编码器所属的包。
package com.stockmanager.system.auth.security;

// 引入 BCrypt 实现，用于执行带随机盐的密码哈希。
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
// 引入 Spring Security 的密码编码器接口。
import org.springframework.security.crypto.password.PasswordEncoder;

// 引入 UTF-8 字节编码。
import java.nio.charset.StandardCharsets;
// 引入消息摘要 API。
import java.security.MessageDigest;
// 引入摘要算法不存在时的异常类型。
import java.security.NoSuchAlgorithmException;
// 引入 Base64，用于把 SHA-256 二进制结果转成文本。
import java.util.Base64;

// 兼容新旧密码格式的编码器：新密码先 SHA-256，再交给 BCrypt，避免 BCrypt 的长度限制。
public final class CompatiblePasswordEncoder implements PasswordEncoder {
    // 新格式前缀，用来区分“SHA-256 后再 BCrypt”的密码摘要。
    static final String PREHASHED_PREFIX = "{bcrypt-sha256}";
    // Spring DelegatingPasswordEncoder 旧 BCrypt 格式使用的前缀。
    private static final String DELEGATING_BCRYPT_PREFIX = "{bcrypt}";
    // 实际执行 BCrypt 编码和校验的对象。
    private final BCryptPasswordEncoder bcrypt;

    // 使用 BCrypt 默认参数创建编码器。
    public CompatiblePasswordEncoder() {
        // 委托给可注入 BCrypt 实例的构造方法，避免重复初始化逻辑。
        this(new BCryptPasswordEncoder());
    }

    // 包可见构造方法，主要用于测试时注入指定的 BCrypt 实现。
    CompatiblePasswordEncoder(BCryptPasswordEncoder bcrypt) {
        // 保存 BCrypt 依赖，供后续编码、匹配和成本升级检查使用。
        this.bcrypt = bcrypt;
    }

    // 将原始密码编码为带新格式前缀的 BCrypt 摘要。
    @Override
    public String encode(CharSequence rawPassword) {
        // 明确拒绝 null，避免产生不可理解的编码结果。
        if (rawPassword == null) {
            // 以参数异常告知调用方传入了非法密码。
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        // 先计算 SHA-256，再用 BCrypt 加盐哈希，最后加上格式前缀。
        return PREHASHED_PREFIX + bcrypt.encode(prehash(rawPassword));
    }

    // 根据摘要前缀选择新格式或旧格式的校验路径。
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        // 任一输入为空，或数据库摘要为空白时，都不可能匹配成功。
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            // 返回 false 而不是抛出异常，符合 PasswordEncoder 的校验语义。
            return false;
        }

        // BCrypt 解析非法摘要或旧格式超长密码时可能抛出参数异常。
        try {
            // 新格式需要对原始密码先做同样的 SHA-256 预哈希。
            if (encodedPassword.startsWith(PREHASHED_PREFIX)) {
                // 去掉格式前缀后，把预哈希结果交给 BCrypt 比较。
                return bcrypt.matches(
                        // 计算待验证密码的 SHA-256 Base64 文本。
                        prehash(rawPassword),
                        // 取出前缀之后真正的 BCrypt 摘要。
                        encodedPassword.substring(PREHASHED_PREFIX.length())
                );
            }

            // 兼容带 {bcrypt} 前缀和不带前缀的历史 BCrypt 摘要。
            String legacyHash = encodedPassword.startsWith(DELEGATING_BCRYPT_PREFIX)
                    // 有前缀时移除前缀再交给 BCrypt。
                    ? encodedPassword.substring(DELEGATING_BCRYPT_PREFIX.length())
                    // 无前缀时直接将原值视为 BCrypt 摘要。
                    : encodedPassword;
            // 旧摘要使用原始密码校验，不做 SHA-256 预哈希。
            return bcrypt.matches(rawPassword, legacyHash);
        } catch (IllegalArgumentException exception) {
            // 非法或过长的历史密码无法匹配时，按校验失败处理。
            return false;
        }
    }

    // 判断摘要是否为旧格式或成本参数较低的格式，以便登录后升级。
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        // null、旧格式和未知格式都要求重新编码为当前标准。
        if (encodedPassword == null || !encodedPassword.startsWith(PREHASHED_PREFIX)) {
            // 返回 true 后，上层可以在合适时机保存新格式摘要。
            return true;
        }
        // 对新格式去掉自定义前缀，再让 BCrypt 判断成本参数是否需要升级。
        return bcrypt.upgradeEncoding(encodedPassword.substring(PREHASHED_PREFIX.length()));
    }

    // 使用 UTF-8 对原始密码做 SHA-256，并转为 BCrypt 可接受的 Base64 文本。
    private static String prehash(CharSequence rawPassword) {
        // 获取 SHA-256 摘要算法实例。
        try {
            // 将密码转成 UTF-8 字节并计算 32 字节摘要。
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            // 将摘要编码成 Base64，作为 BCrypt 的输入文本。
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 Java 标准算法；若不可用则说明运行环境严重异常。
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
