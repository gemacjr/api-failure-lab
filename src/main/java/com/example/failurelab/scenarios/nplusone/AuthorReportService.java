package com.example.failurelab.scenarios.nplusone;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class AuthorReportService {

    private final AuthorRepository authors;

    AuthorReportService(AuthorRepository authors) {
        this.authors = authors;
    }

    /** 1 query for the authors, then author.getBooks().size() fires 1 more query PER author. */
    @Transactional(readOnly = true)
    public AuthorReport brokenReport() {
        return summarize(authors.findAll());
    }

    /** Books are fetched in the same statement as the authors, so size() touches no database. */
    @Transactional(readOnly = true)
    public AuthorReport fixedReport() {
        return summarize(authors.findAllWithBooks());
    }

    private static AuthorReport summarize(List<Author> all) {
        int books = all.stream().mapToInt(author -> author.getBooks().size()).sum();
        return new AuthorReport(all.size(), books);
    }
}
