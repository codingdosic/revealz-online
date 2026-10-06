package com.revealz.backend.shop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CardCatalog {
    private static final Pattern ID = Pattern.compile("(?m)^id = (\\d+)\\s*$");
    private static final Pattern NAME = Pattern.compile("(?m)^card_name = \"([^\"]*)\"");
    private static final Pattern TRIGGER = Pattern.compile("(?m)^trigger_type = (\\d+)\\s*$");
    private static final int TOKEN = 128;
    private volatile CatalogData data;

    public List<Integer> nonTokenIds() {
        return data().ids();
    }

    public String name(int id) {
        return data().names().getOrDefault(id, "카드 " + id);
    }

    private CatalogData data() {
        CatalogData cached = data;
        if (cached != null) return cached;
        synchronized (this) {
            if (data == null) data = load();
            return data;
        }
    }

    private CatalogData load() {
        Path root = locateRoot();
        if (root == null) return new CatalogData(List.of(), Map.of());
        List<Integer> ids = new ArrayList<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".tres")).forEach(path -> read(path, ids, names));
        } catch (IOException ignored) {
            return new CatalogData(List.of(), Map.of());
        }
        return new CatalogData(ids.stream().distinct().sorted().toList(), Map.copyOf(names));
    }

    private Path locateRoot() {
        String configured = System.getenv("CARD_RESOURCES_PATH");
        List<Path> candidates = configured == null || configured.isBlank()
                ? List.of(Path.of("resources/cards"), Path.of("../resources/cards"), Path.of("/app/resources/cards"))
                : List.of(Path.of(configured));
        return candidates.stream().map(Path::toAbsolutePath).filter(Files::isDirectory).findFirst().orElse(null);
    }

    private void read(Path path, List<Integer> ids, Map<Integer, String> names) {
        try {
            String text = Files.readString(path);
            Matcher id = ID.matcher(text);
            if (!id.find()) return;
            Matcher trigger = TRIGGER.matcher(text);
            int flags = trigger.find() ? Integer.parseInt(trigger.group(1)) : 0;
            int cardId = Integer.parseInt(id.group(1));
            if ((flags & TOKEN) == 0) {
                ids.add(cardId);
                Matcher name = NAME.matcher(text);
                if (name.find() && !name.group(1).isBlank()) names.put(cardId, name.group(1));
            }
        } catch (IOException | NumberFormatException ignored) {
            // A malformed resource is skipped, matching the old catalog scan.
        }
    }

    private record CatalogData(List<Integer> ids, Map<Integer, String> names) { }
}
