create table if not exists industrendindia.social_media_posts(
  social_post_id uuid primary key,
  prompt text not null,
  caption text,
  provider varchar(30) not null default 'openai',
  model varchar(80),
  status varchar(24) not null check(status in('GENERATING','GENERATED','REVIEWED','PUBLISHING','PUBLISHED','FAILED')),
  image_bytes bytea,
  image_content_type varchar(80),
  error_message varchar(500),
  facebook_post_id varchar(180),
  created_by uuid not null,
  created_at timestamptz not null default clock_timestamp(),
  reviewed_at timestamptz,
  published_at timestamptz,
  updated_at timestamptz not null default clock_timestamp()
);
create index if not exists ix_social_media_posts_created on industrendindia.social_media_posts(created_at desc);
create index if not exists ix_social_media_posts_status on industrendindia.social_media_posts(status,updated_at desc);
