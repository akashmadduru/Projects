package com.acme.ecommerce.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailRendererTest {

  private final EmailRenderer renderer = new EmailRenderer();

  @Test
  void phoneClaimAttemptShowsOnlyTheLastFourDigitsAndNamesNoOneElse() {
    EmailContent mail =
        renderer.render(
            EmailTemplate.PHONE_CLAIM_ATTEMPT,
            Map.of(
                "name", "Asha", "phoneTail", "4321", "link", "https://shop.test/account/security"));

    assertThat(mail.text())
        .contains("Hi Asha,")
        .contains("number ending 4321")
        .contains("It is still only on yours")
        .contains("https://shop.test/account/security");
    assertThat(mail.html()).contains("number ending 4321");
  }

  @Test
  void passwordSetExplainsTheResetAndLinksToSecurity() {
    EmailContent mail =
        renderer.render(
            EmailTemplate.PASSWORD_SET,
            Map.of("name", "Asha", "link", "https://shop.test/account/security"));

    assertThat(mail.subject()).isEqualTo("A password was set on your account");
    assertThat(mail.text()).contains("Hi Asha,").contains("https://shop.test/account/security");
  }

  @Test
  void newTemplatesEscapeNameTailAndLinkInHtml() {
    for (EmailTemplate template :
        new EmailTemplate[] {EmailTemplate.PHONE_CLAIM_ATTEMPT, EmailTemplate.PASSWORD_SET}) {
      EmailContent mail =
          renderer.render(
              template,
              Map.of(
                  "name", "<script>alert(1)</script>",
                  "phoneTail", "<b>9</b>",
                  "link", "https://x.test/?a=1&b=\"2\""));

      assertThat(mail.html())
          .doesNotContain("<script>")
          .doesNotContain("<b>9</b>")
          .contains("&lt;script&gt;")
          .contains("&amp;b=&quot;2&quot;");
    }
  }
}
