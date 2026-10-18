package in.industrend.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class SocialMediaService {
  record Post(UUID id,String prompt,String caption,String status,String errorMessage,String facebookPostId,boolean imageReady) {}
  private final JdbcClient jdbc; private final ConfigClient config; private final ObjectMapper json;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
  SocialMediaService(JdbcClient jdbc,ConfigClient config,ObjectMapper json){this.jdbc=jdbc;this.config=config;this.json=json;}

  Post create(String prompt,String caption,UUID admin){
    if(prompt==null||prompt.trim().length()<10||prompt.length()>2000)throw new IllegalArgumentException("Describe the image in 10 to 2,000 characters");
    var id=UUID.randomUUID();
    jdbc.sql("insert into industrendindia.social_media_posts(social_post_id,prompt,caption,provider,status,created_by) values(:id,:p,:c,'google','GENERATING',:a)").param("id",id).param("p",prompt.trim()).param("c",caption==null?"":caption.trim()).param("a",admin).update();
    CompletableFuture.runAsync(()->generate(id,prompt.trim())); return get(id);
  }

  Post upload(byte[] bytes,String contentType,String caption,UUID admin){
    if(bytes==null||bytes.length==0)throw new IllegalArgumentException("Choose an image to upload");
    if(bytes.length>10*1024*1024)throw new IllegalArgumentException("The image must be 10 MB or smaller");
    if(!java.util.Set.of("image/jpeg","image/png","image/webp").contains(contentType))throw new IllegalArgumentException("Only JPEG, PNG and WebP images are supported");
    var id=UUID.randomUUID();
    jdbc.sql("insert into industrendindia.social_media_posts(social_post_id,prompt,caption,provider,status,image_bytes,image_content_type,created_by) values(:id,'Uploaded image',:c,'upload','GENERATED',:b,:t,:a)").param("id",id).param("c",caption==null?"":caption.trim()).param("b",bytes).param("t",contentType).param("a",admin).update();
    return get(id);
  }

  void generate(UUID id,String prompt){
    try{
      var key=config.required("social.google.api_key");
      var model=config.get("social.google.model","gemini-3.1-flash-lite-image");
      var endpoint=config.get("social.google.endpoint","https://generativelanguage.googleapis.com/v1beta/interactions");
      var body=json.writeValueAsString(Map.of("model",model,"input",prompt,"response_format",Map.of("type","image","mime_type","image/png","aspect_ratio","1:1","image_size","1K"),"generation_config",Map.of("thinking_level","minimal")));
      var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(java.time.Duration.ofMinutes(3)).header("x-goog-api-key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()/100!=2)throw new IllegalStateException("Google image API returned HTTP "+response.statusCode()+": "+providerError(response.body()));
      var root=json.readTree(response.body()); var image=root.path("output_image");
      var encoded=image.path("data").asText(); if(encoded.isBlank())throw new IllegalStateException("Google image API returned no image");
      var mime=image.path("mime_type").asText("image/png");
      jdbc.sql("update industrendindia.social_media_posts set image_bytes=:b,image_content_type=:t,model=:m,status='GENERATED',updated_at=clock_timestamp() where social_post_id=:id").param("b",Base64.getDecoder().decode(encoded)).param("t",mime).param("m",model).param("id",id).update();
    }catch(Exception e){jdbc.sql("update industrendindia.social_media_posts set status='FAILED',error_message=:e,updated_at=clock_timestamp() where social_post_id=:id").param("e",safe(e.getMessage())).param("id",id).update();}
  }

  Post get(UUID id){return jdbc.sql("select social_post_id,prompt,caption,status,error_message,facebook_post_id,image_bytes is not null from industrendindia.social_media_posts where social_post_id=:id").param("id",id).query((r,n)->new Post(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7))).optional().orElseThrow(()->new IllegalArgumentException("Social post was not found"));}
  byte[] image(UUID id){return jdbc.sql("select image_bytes from industrendindia.social_media_posts where social_post_id=:id and image_bytes is not null").param("id",id).query(byte[].class).optional().orElseThrow(()->new IllegalArgumentException("Generated image is not ready"));}
  String imageContentType(UUID id){return jdbc.sql("select image_content_type from industrendindia.social_media_posts where social_post_id=:id and image_bytes is not null").param("id",id).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Image is not ready"));}
  Post review(UUID id){jdbc.sql("update industrendindia.social_media_posts set status='REVIEWED',reviewed_at=clock_timestamp(),updated_at=clock_timestamp() where social_post_id=:id and status in('GENERATED','REVIEWED')").param("id",id).update();return get(id);}

  Post publish(UUID id,String caption){
    var post=get(id); if(!"REVIEWED".equals(post.status()))throw new IllegalArgumentException("Review the image before publishing");
    var finalCaption=caption==null?post.caption():caption.trim();
    jdbc.sql("update industrendindia.social_media_posts set status='PUBLISHING',caption=:c,updated_at=clock_timestamp() where social_post_id=:id").param("c",finalCaption).param("id",id).update();
    try{
      var page=config.required("social.meta.page_id"); var token=config.required("social.meta.page_access_token"); var version=config.get("social.meta.graph_version","v24.0");
      var boundary="----IndusTrend"+UUID.randomUUID().toString().replace("-",""); var out=new ByteArrayOutputStream();
      write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n"+finalCaption+"\r\n");
      var imageType=imageContentType(id); var extension="image/jpeg".equals(imageType)?"jpg":"image/webp".equals(imageType)?"webp":"png";
      write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"source\"; filename=\"industrend."+extension+"\"\r\nContent-Type: "+imageType+"\r\n\r\n"); out.write(image(id)); write(out,"\r\n--"+boundary+"--\r\n");
      var uri=URI.create("https://graph.facebook.com/"+version+"/"+page+"/photos?published=true");
      var request=HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofMinutes(2)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofString()); if(response.statusCode()/100!=2)throw new IllegalStateException("Facebook returned HTTP "+response.statusCode()+": "+providerError(response.body()));
      var root=json.readTree(response.body()); var facebookId=root.hasNonNull("post_id")?root.get("post_id").asText():root.path("id").asText();
      jdbc.sql("update industrendindia.social_media_posts set status='PUBLISHED',facebook_post_id=:f,published_at=clock_timestamp(),updated_at=clock_timestamp() where social_post_id=:id").param("f",facebookId).param("id",id).update(); return get(id);
    }catch(Exception e){jdbc.sql("update industrendindia.social_media_posts set status='REVIEWED',error_message=:e,updated_at=clock_timestamp() where social_post_id=:id").param("e",safe(e.getMessage())).param("id",id).update();throw new IllegalStateException("Facebook publishing failed: "+safe(e.getMessage()));}
  }
  private String providerError(String body){try{JsonNode root=json.readTree(body);var message=root.path("error").path("message").asText();return message.isBlank()?"External provider rejected the request":safe(message);}catch(Exception e){return"External provider rejected the request";}}
  private static void write(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));}
  private static String safe(String value){if(value==null)return"External service request failed";return value.length()>480?value.substring(0,480):value;}
}
