package com.revealz.backend.catalog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

    static final String PATH = "/v1/shop/catalog";

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping(PATH)
    CatalogResponse catalog() {
        return service.loadPublicCatalog();
    }

    @RequestMapping(path = PATH, method = RequestMethod.HEAD)
    ResponseEntity<Void> head() {
        return ResponseEntity.status(405).header(HttpHeaders.ALLOW, "GET, OPTIONS").build();
    }

    @RequestMapping(path = PATH, method = RequestMethod.OPTIONS)
    ResponseEntity<Void> options() {
        return ResponseEntity.noContent().header(HttpHeaders.ALLOW, "GET, OPTIONS").build();
    }
}
