package dev.wulnry.mossboss.config;

import java.util.List;

/**
 * Tek bir ödül sıralamasını temsil eden değişmez veri nesnesi.
 *
 * @param name     oyunculara gösterilecek ödül adı (ör. "Kit Kasası Anahtarı")
 * @param commands konsol üzerinden çalıştırılacak komutlar; {player} yer tutucusu desteklenir
 */
public record RewardEntry(String name, List<String> commands) {}
