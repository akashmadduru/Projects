package com.acme.ecommerce.notification.campaign;

import com.acme.ecommerce.notification.campaign.CampaignService.Campaign;
import com.acme.ecommerce.notification.campaign.CampaignService.CampaignRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/campaigns")
@PreAuthorize("hasRole('ADMIN')")
public class CampaignController {

  private final CampaignService campaigns;

  public CampaignController(CampaignService campaigns) {
    this.campaigns = campaigns;
  }

  @GetMapping
  public List<Campaign> list() {
    return campaigns.list();
  }

  @GetMapping("/{id}")
  public Campaign get(@PathVariable UUID id) {
    return campaigns.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Campaign create(@RequestBody CampaignRequest request) {
    return campaigns.create(request);
  }

  @PostMapping("/{id}/send")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Campaign send(@PathVariable UUID id) {
    return campaigns.send(id);
  }
}
