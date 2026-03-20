package com.example.claudetest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalControllerTest {

    @Mock
    private JournalEntryRepository repository;

    @InjectMocks
    private JournalController controller;

    private JournalEntry sampleEntry;

    @BeforeEach
    void setUp() {
        sampleEntry = new JournalEntry("Test content", LocalDateTime.of(2026, 3, 20, 12, 0));
        sampleEntry.setId(1L);
    }

    @Test
    void getAllEntries_returnsEntriesFromRepository() {
        List<JournalEntry> entries = List.of(sampleEntry);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(entries);

        List<JournalEntry> result = controller.getAllEntries();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Test content");
        verify(repository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void getAllEntries_returnsEmptyListWhenNoEntries() {
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        List<JournalEntry> result = controller.getAllEntries();

        assertThat(result).isEmpty();
    }

    @Test
    void createEntry_savesEntryWithContentAndTimestamp() {
        when(repository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        JournalEntry result = controller.createEntry(Map.of("content", "New entry"));

        assertThat(result.getContent()).isEqualTo("New entry");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        verify(repository).save(any(JournalEntry.class));
    }

    @Test
    void deleteEntry_deletesById() {
        ResponseEntity<Void> response = controller.deleteEntry(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(repository).deleteById(1L);
    }
}
