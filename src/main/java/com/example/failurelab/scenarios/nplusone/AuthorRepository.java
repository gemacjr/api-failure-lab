package com.example.failurelab.scenarios.nplusone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /** One SQL statement: authors LEFT JOIN books. Hibernate 6+ de-duplicates the root entities. */
    @Query("select a from Author a left join fetch a.books")
    List<Author> findAllWithBooks();
}
