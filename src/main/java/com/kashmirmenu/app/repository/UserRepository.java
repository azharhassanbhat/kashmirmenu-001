package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("""
       SELECT u
       FROM User u
       JOIN FETCH u.role
       WHERE u.email = :email
       """)
    Optional<User> findByEmail(@Param("email") String email);

    boolean existsByEmail(String email);

    /** Staff of one restaurant, for the analytics staff filter. */
    List<User> findByRestaurantIdOrderByNameAsc(Long restaurantId);

    /** Whether the platform already has an owner; the bootstrap must never create a second. */
    /**
     * Underscore states the traversal (role -> name) rather than leaving Spring Data to split
     * "RoleName" by guesswork. User has no roleName property, so resolution relied on the
     * fallback - and a derived query that fails to resolve fails at CONTEXT STARTUP, not at the
     * call, so the whole application would not boot.
     */
    boolean existsByRole_Name(RoleName name);

}