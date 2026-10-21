package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);

}