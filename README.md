# Indus Trend Social Service

Standalone Spring Boot service for authenticated AI image generation, review, and Facebook Page publishing.

- Default port: `8096`
- Database schema: `industrendindia`
- Flyway table: `flyway_social_history`
- Image provider: Google Gemini API (`gemini-3.1-flash-lite-image`)
- Publishing provider: Meta Graph API Page Photos endpoint
- Authentication: admin session validation through `industrend-identity-service`

Provider credentials are loaded at runtime from `industrend-config-service`; never commit API keys or Page access tokens.
