package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.KeyPair;
import java.util.HexFormat;

/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    // pi-airplay patch: a receiver that keeps the same identity across restarts
    public static PairingIdentity fromSeed(byte[] seed) {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKeySpec priv = new EdDSAPrivateKeySpec(seed, spec);
        EdDSAPublicKeySpec pub = new EdDSAPublicKeySpec(priv.getA(), spec);
        return new PairingIdentity(new KeyPair(new EdDSAPublicKey(pub), new EdDSAPrivateKey(priv)));
    }

    // pi-airplay patch
    public byte[] seed() {
        return ((EdDSAPrivateKey) keyPair.getPrivate()).getSeed();
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        return HexFormat.of().formatHex(publicKey());
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
