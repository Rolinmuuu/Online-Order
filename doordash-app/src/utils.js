export const login = (credentials) => {
  const loginUrl = `/login?username=${credentials.username}&password=${credentials.password}`;

  // fetch -> Promose.then -> Promose.then
  return fetch(loginUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
  }).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Login failed");
    }
  });
};

export const signup = (data) => {
  const signupUrl = "/signup";

  return fetch(signupUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  }).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Signup failed");
    }
  });
};

export const getMenus = (restId) => {
  return fetch(`/restaurant/${restId}/menu`).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Failed to fetch menu");
    }
    return response.json();
  });
};

export const getRestaurants = () => {
  return fetch("/restaurants/menu").then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Failed to fetch restaurants");
    }
    return response.json();
  });
};

export const getCart = () => {
  return fetch("/cart").then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Failed to fetch cart");
    }
    return response.json();
  });
};

export const checkOut = () => {
  return fetch("/cart/checkout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
  }).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Checkout failed");
    }
  });
};

export const addItemToCart = (itemId) => {
  const payload = {
    menu_id: itemId,
  };
  return fetch("/cart", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  }).then((response) => {
    if (response.status < 200 || response.status >= 300) {
      throw Error("Failed to add menu item to shopping cart");
    }
  });
};
