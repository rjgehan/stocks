package com.stocks.marketdata.mapper;

import com.stocks.contract.marketdata.dto.NewsItemDto;
import com.stocks.contract.marketdata.model.NewsItem;
import org.springframework.stereotype.Component;

/** {@code model.NewsItem → NewsItemDto}: third-party headline and summary are wrapped in {@code untrusted} (§17.8). */
@Component
public class NewsItemDtoMapper {

  public static NewsItemDto toDto(NewsItem n) {
    return new NewsItemDto(n.id(), n.publishedAt(), n.source(), n.url(), n.symbols() == null ? java.util.List.of()
        : n.symbols(), n.imageUrl(), new NewsItemDto.UntrustedNewsDto(n.headline(), n.summary()));
  }
}
