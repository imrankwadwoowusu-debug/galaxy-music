package com.music.bitchord.desktop

import com.music.bitchord.desktop.secrets.SecretServiceApi
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/** The platform's own password store, for the credentials this app holds. */
internal object DesktopSecretStore {

    /** Plain-text transport. */
    private const val PLAIN = "plain"

    private const val DEFAULT_COLLECTION = "/org/freedesktop/secrets/aliases/default"

    private const val KEYCHAIN_SERVICE = "BitChord"

    /**
     * Runs [block] with an open Secret Service session, or returns null.
     *
     * Secret Service is a Linux session-bus API. Elsewhere there is no bus to reach, and the failed
     * connect takes about ten seconds on macOS, which every account switch and sign-out paid.
     */
    private fun <T> withService(
        block: (DBusConnection, SecretServiceApi.Service, DBusPath) -> T?,
    ): T? = if (!DesktopPlatform.isLinux) null else runCatching {
        DBusConnectionBuilder.forSessionBus().withShared(false).build().use { connection ->
            val service = connection.getRemoteObject(
                SecretServiceApi.BUS_NAME,
                SecretServiceApi.SERVICE_PATH,
                SecretServiceApi.Service::class.java,
            )
            val session = service.OpenSession(PLAIN, Variant("")).session
            block(connection, service, session)
        }
    }.getOrElse {
        DesktopTrackLog.log("secret store unavailable: ${it.message}")
        null
    }

    /** The first secret stored under [attributes], as bytes. */
    fun lookup(attributes: Map<String, String>): ByteArray? {
        if (DesktopPlatform.isMac) return macLookup(attributes)
        return withService { _, service, session ->
            val found = service.SearchItems(attributes)
            val items = buildList {
                addAll(found.unlocked)
                if (found.locked.isNotEmpty()) {
                    runCatching { service.Unlock(found.locked) }.getOrNull()?.let { addAll(it.unlocked) }
                }
            }
            if (items.isEmpty()) return@withService null
            service.GetSecrets(items, session).values.firstOrNull()?.value
        }
    }

    /** Stores [secret] under [attributes], replacing whatever was there. */
    fun store(label: String, attributes: Map<String, String>, secret: ByteArray): Boolean {
        if (DesktopPlatform.isMac) return macStore(label, attributes, secret)
        return withService { connection, service, session ->
            val collection = runCatching { service.ReadAlias("default")?.path }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?: DEFAULT_COLLECTION
            val properties = mapOf<String, Variant<*>>(
                "org.freedesktop.Secret.Item.Label" to Variant(label),
                "org.freedesktop.Secret.Item.Attributes" to Variant(attributes, "a{ss}"),
            )
            val item = connection.getRemoteObject(
                SecretServiceApi.BUS_NAME,
                collection,
                SecretServiceApi.Collection::class.java,
            )
            item.CreateItem(
                properties,
                SecretServiceApi.Secret(session, ByteArray(0), secret, "text/plain; charset=utf8"),
                true,
            )
            true
        } ?: false
    }

    /** Forgets everything stored under [attributes]. */
    fun remove(attributes: Map<String, String>): Boolean {
        if (DesktopPlatform.isMac) return macRemove(attributes)
        return withService { connection, service, _ ->
            val found = service.SearchItems(attributes)
            (found.unlocked + found.locked).forEach { path ->
                runCatching {
                    connection.getRemoteObject(
                        SecretServiceApi.BUS_NAME,
                        path.path,
                        SecretServiceApi.Item::class.java,
                    ).Delete()
                }
            }
            true
        } ?: false
    }

    /** Whether a store is actually reachable, for the settings screen to say so. */
    fun isAvailable(): Boolean = when {
        DesktopPlatform.isMac -> DesktopMacKeychain.isAvailable()
        DesktopPlatform.isLinux -> withService { _, _, _ -> true } == true
        else -> false
    }

    private fun macAccount(attributes: Map<String, String>): String =
        attributes["account"]
            ?: attributes.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }

    private fun macLookup(attributes: Map<String, String>): ByteArray? =
        DesktopMacKeychain.findGenericPassword(KEYCHAIN_SERVICE, macAccount(attributes))

    private fun macStore(label: String, attributes: Map<String, String>, secret: ByteArray): Boolean =
        DesktopMacKeychain.storeGenericPassword(
            service = KEYCHAIN_SERVICE,
            account = macAccount(attributes),
            label = label,
            secret = secret,
        )

    private fun macRemove(attributes: Map<String, String>): Boolean =
        DesktopMacKeychain.deleteGenericPassword(KEYCHAIN_SERVICE, macAccount(attributes))
}
