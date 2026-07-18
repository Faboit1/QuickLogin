package dev.quicklogin.storage;

/**
 * A stored account record. Keyed by lower-cased username to mirror
 * AuthMeReloaded's name-based account model.
 *
 * @param usernameLower lower-cased name (primary key)
 * @param username      name with original casing (for display / re-register)
 * @param premiumUuid   the online-mode Mojang UUID, or {@code null} if unknown
 * @param premium       whether this account is a verified premium Java account
 * @param password      the generated password used to register the account in
 *                      AuthMe, or {@code null} for accounts we do not manage
 * @param firstSeen     epoch millis of first join
 * @param lastLogin     epoch millis of last successful auto-login, or 0
 */
public record PlayerData(
        String usernameLower,
        String username,
        String premiumUuid,
        boolean premium,
        String password,
        long firstSeen,
        long lastLogin
) {
}
