package org.springframework.data.jpa.repository;
public interface JpaRepository<T, ID> { <S extends T> S save(S entity); java.util.Optional<T> findById(ID id); }
