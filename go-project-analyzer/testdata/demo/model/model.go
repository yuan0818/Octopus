package model

// User is a plain struct with exported fields.
type User struct {
	ID   string
	Name string
}

// DisplayName returns the user's display name.
func (u *User) DisplayName() string {
	return u.Name
}
