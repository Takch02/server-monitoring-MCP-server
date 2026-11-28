package com.kakao.kakao_test.repository;

import com.kakao.kakao_test.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, String> {}
