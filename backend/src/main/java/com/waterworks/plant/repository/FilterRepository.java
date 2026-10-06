package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.Filter;

public interface FilterRepository extends JpaRepository<Filter, Long> {

    Optional<Filter> findByFilterCode(String filterCode);

    List<Filter> findByType(String type);

    List<Filter> findByStatus(String status);
}
