package com.xander.lab.publishing.application;

import com.xander.lab.identity.infrastructure.UserMapper;
import com.xander.lab.publishing.api.BlogPublishRhythmVO;
import com.xander.lab.publishing.infrastructure.BlogCategoryMapper;
import com.xander.lab.publishing.infrastructure.BlogPostMapper;
import com.xander.lab.publishing.infrastructure.BlogPostViewMapper;
import com.xander.lab.publishing.infrastructure.BlogTagMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlogPublishRhythmServiceTest {

    @Test
    void returnsMondayToSundayCountsAndAverage() {
        BlogPostMapper postMapper = mock(BlogPostMapper.class);
        when(postMapper.selectPublishedDates(eq(7L), any(), any())).thenReturn(List.of(
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-08-27")));
        BlogService service = new BlogService(postMapper, mock(BlogCategoryMapper.class),
                mock(BlogTagMapper.class), mock(BlogPostViewMapper.class), mock(UserMapper.class),
                mock(StringRedisTemplate.class));

        BlogPublishRhythmVO rhythm = service.getWeeklyPublishRhythm(7L, LocalDate.parse("2026-08-29"));

        assertThat(rhythm.getWeekStart()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(rhythm.getDailyAverage()).isEqualByComparingTo("0.4");
        assertThat(rhythm.getDays()).extracting(BlogPublishRhythmVO.DayCount::count)
                .containsExactly(1L, 0L, 0L, 2L, 0L, 0L, 0L);
    }
}
