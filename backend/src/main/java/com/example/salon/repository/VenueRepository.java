package com.example.salon.repository;

import com.example.salon.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findByStatus(Integer status);

    List<Venue> findByNameContaining(String name);
}
