
package com.networknt.clientmoduletest.model;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;

public class User {

    
    private Integer id;
    
    private Boolean completed;
    
    private String title;
    
    private Integer userId;
    

    public User () {
    }

    
    
    @JsonProperty("id")
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }
    
    
    
    @JsonProperty("completed")
    public Boolean getCompleted() {
        return completed;
    }

    public void setCompleted(Boolean completed) {
        this.completed = completed;
    }
    
    
    
    @JsonProperty("title")
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
    
    
    
    @JsonProperty("userId")
    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }
    
    

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        User User = (User) o;

        return Objects.equals(id, User.id) &&
        Objects.equals(completed, User.completed) &&
        Objects.equals(title, User.title) &&
        
        Objects.equals(userId, User.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, completed, title,  userId);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("class User {\n");
        
        sb.append("    id: ").append(toIndentedString(id)).append("\n");
        sb.append("    completed: ").append(toIndentedString(completed)).append("\n");
        sb.append("    title: ").append(toIndentedString(title)).append("\n");
        sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert the given object to string with each line indented by 4 spaces
     * (except the first line).
     */
    private String toIndentedString(Object o) {
        if (o == null) {
            return "null";
        }
        return o.toString().replace("\n", "\n    ");
    }
}
