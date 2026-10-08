package io.github.ricardoord.opswatch.notification.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    Page<NotificationChannel> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** Every channel counts toward the quota, disabled ones included: channels are never deleted logically. */
    long countByOrganizationId(UUID organizationId);

    /**
     * Deletes the channels limited to a project, and with them their deliveries ({@code ON DELETE CASCADE}). A bulk
     * delete is safe here, unlike a bulk update: a {@code PATCH} that read one of them before fails on its version
     * instead of writing it back.
     *
     * @return how many it deleted
     */
    @Modifying
    @Query("DELETE FROM NotificationChannel c WHERE c.projectId = :projectId")
    int deleteByProjectId(UUID projectId);

    /** The same for every channel of an organization. */
    @Modifying
    @Query("DELETE FROM NotificationChannel c WHERE c.organizationId = :organizationId")
    int deleteByOrganizationId(UUID organizationId);
}
