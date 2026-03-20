package com.example.claudetest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/entries")
public class JournalController {

    private final JournalEntryRepository repository;

    public JournalController(JournalEntryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<JournalEntry> getAllEntries() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public JournalEntry createEntry(@RequestBody Map<String, String> body) {
        JournalEntry entry = new JournalEntry();
        entry.setContent(body.get("content"));
        entry.setCreatedAt(LocalDateTime.now());
        return repository.save(entry);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
