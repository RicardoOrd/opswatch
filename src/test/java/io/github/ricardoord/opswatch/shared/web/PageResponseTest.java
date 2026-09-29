package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void mapsItemsAndMetadata() {
        var page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.from(page, number -> "item-" + number);

        assertThat(response.items()).containsExactly("item-1", "item-2");
        assertThat(response.page()).isEqualTo(new PageResponse.PageMetadata(1, 2, 5, 3));
    }
}
