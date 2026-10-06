package com.revealz.backend.catalog;

final class CatalogDatabaseNotConfiguredException extends RuntimeException {

    CatalogDatabaseNotConfiguredException() {
        super("meta_db_not_configured");
    }
}
