import { Button, Form, Input, message, Modal } from "antd";
import { UserOutlined, LockOutlined } from "@ant-design/icons";
import { signup } from "../utils";
import React from "react";

class SignupForm extends React.Component {
  state = {
    displayModal: false,
  };

  handleCancel = () => {
    this.setState({ displayModal: false });
  };

  signupOnClick = () => {
    this.setState({ displayModal: true });
  };
  onFinish = (data) => {
    signup(data)
      .then(() => {
        this.setState({ displayModal: false });
        message.success("Signup successful!");
      })
      .catch((err) => {
        message.error("Signup failed.");
      });
  };
  render() {
    return (
      <>
        <Button shape="round" type="primary" onClick={this.signupOnClick}>
          Register
        </Button>

        <Modal
          title="Register"
          open={this.state.displayModal}
          onCancel={this.handleCancel}
          footer={null}
          destroyOnClose={true}
        >
          <Form
            name="normal_register"
            initialValues={{ remember: true }}
            onFinish={this.onFinish}
            preserve={false}
          >
            <Form.Item
              name="email"
              rules={[{ required: true, message: "Please input your Email!" }]}
            >
              <Input prefix={<UserOutlined />} placeholder="Email" />
            </Form.Item>
            <Form.Item
              name="password"
              rules={[
                { required: true, message: "Please input your Password!" },
              ]}
            >
              <Input prefix={<LockOutlined />} placeholder="Password" />
            </Form.Item>
            <Form.Item
              name="first_name"
              rules={[
                { required: true, message: "Please input your First Name!" },
              ]}
            >
              <Input placeholder="First Name" />
            </Form.Item>
            <Form.Item
              name="last_name"
              rules={[
                { required: true, message: "Please input your Last Name!" },
              ]}
            >
              <Input placeholder="Last Name" />
            </Form.Item>

            <Form.Item>
              <Button type="primary" htmlType="submit">
                Register
              </Button>
            </Form.Item>
          </Form>
        </Modal>
      </>
    );
  }
}

export default SignupForm;
