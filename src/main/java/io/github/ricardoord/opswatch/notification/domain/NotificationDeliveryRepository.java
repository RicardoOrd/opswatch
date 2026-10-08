package io.github.ricardoord.opswatch.notification.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Reads only: {@link DeliveryQueue} writes. */
public interface NotificationDeliveryRepository extends Repository<NotificationDelivery, UUID> {

    Optional<NotificationDelivery> findById(UUID id);

    Page<NotificationDelivery> findByChannelId(UUID channelId, Pageable pageable);
}
