package com.example.salon.controller;

import com.example.salon.dto.VenueSaveResponse;
import com.example.salon.entity.Venue;
import com.example.salon.repository.VenueRepository;
import com.example.salon.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/venue")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VenueController {

    private final VenueRepository venueRepository;
    private final VenueService venueService;

    @GetMapping
    public ResponseEntity<List<Venue>> getAllVenues() {
        return ResponseEntity.ok(venueRepository.findByStatus(1));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Venue> getVenueById(@PathVariable Long id) {
        return venueRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Venue> createVenue(@RequestBody Venue venue) {
        venue.setId(null);
        venue.setStatus(1);
        venue.setVersion(null);
        return ResponseEntity.ok(venueRepository.save(venue));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VenueSaveResponse> updateVenue(@PathVariable Long id, @RequestBody Venue venue) {
        return ResponseEntity.ok(venueService.updateVenue(id, venue));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<VenueSaveResponse> deleteVenue(@PathVariable Long id) {
        return ResponseEntity.ok(venueService.deactivateVenue(id));
    }
}
