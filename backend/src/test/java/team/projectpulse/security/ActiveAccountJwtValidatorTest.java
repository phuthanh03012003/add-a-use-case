package team.projectpulse.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import team.projectpulse.user.UserService;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** Oracles: FR-SEC-active-account and stu.md's deactivate-authentication-regression row. */
@ExtendWith(MockitoExtension.class)
class ActiveAccountJwtValidatorTest {

    @Mock
    UserService userService;
    @InjectMocks
    ActiveAccountJwtValidator validator;

    private static KeyPair keys;

    @BeforeAll
    static void createSigningKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    static Stream<Object> validIds() {
        return Stream.of(17, 17L, "17");
    }

    @ParameterizedTest
    @MethodSource("validIds")
    void activeAccountAcceptsSupportedSignedIdRepresentations(Object id) {
        given(this.userService.isAccountEnabled(17)).willReturn(true);
        assertThat(this.validator.validate(token(id)).hasErrors()).isFalse();
    }

    static Stream<Object> invalidIds() {
        return Stream.of(0, -1, 0L, -1L, 2147483648L, Long.MAX_VALUE,
                "", "0", "-1", "2147483648", "17.5", "garbage", " 17", "17 ",
                17.5, true, List.of(17), Map.of("id", 17));
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void malformedOrOutOfRangeIdFailsClosedWithoutAccountLookup(Object id) {
        assertThat(this.validator.validate(token(id)).hasErrors()).isTrue();
        verifyNoInteractions(this.userService);
    }

    @Test
    void missingIdFailsClosed() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "RS256").subject("student").build();
        assertThat(this.validator.validate(token).hasErrors()).isTrue();
        verifyNoInteractions(this.userService);
    }

    @Test
    void inactiveOrMissingAccountRejectsTokenWithoutDisclosingWhich() {
        given(this.userService.isAccountEnabled(17)).willReturn(false);
        var result = this.validator.validate(token(17));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo("invalid_token");
            assertThat(error.getDescription()).isEqualTo("The account is unavailable.");
        });
    }

    @Test
    void realSignedTokenRemainsSubjectToCurrentAccountStatus() {
        given(this.userService.isAccountEnabled(17)).willReturn(true, false);
        NimbusJwtDecoder decoder = decoder();
        Instant now = Instant.now();
        String encoded = encode(keys, now, now.plusSeconds(600));
        assertThat(decoder.decode(encoded).getSubject()).isEqualTo("student");
        assertThatThrownBy(() -> decoder.decode(encoded)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void activeAccountDoesNotMakeAnExpiredTokenValid() {
        given(this.userService.isAccountEnabled(17)).willReturn(true);
        Instant now = Instant.now();
        String expired = encode(keys, now.minusSeconds(600), now.minusSeconds(300));
        assertThatThrownBy(() -> decoder().decode(expired)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void activeAccountCheckDoesNotReplaceSignatureVerification() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Instant now = Instant.now();
        String forged = encode(generator.generateKeyPair(), now, now.plusSeconds(600));
        assertThatThrownBy(() -> decoder().decode(forged)).isInstanceOf(JwtException.class);
        verifyNoInteractions(this.userService);
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), this.validator));
        return decoder;
    }

    private static Jwt token(Object id) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("student").claim("userId", id).build();
    }

    private static String encode(KeyPair signingKeys, Instant issued, Instant expires) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) signingKeys.getPublic())
                .privateKey((RSAPrivateKey) signingKeys.getPrivate()).build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("student").issuedAt(issued).expiresAt(expires)
                .claim("userId", 17).build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
