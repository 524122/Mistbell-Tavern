URL: https://docs.tavoai.dev/en/guides/api-setting/get-key/openai-protocol/
STATUS: 200

Guide
API & Models
API Account Registration and Key Acquisition

Copy Page

Custom (OpenAI Protocol) Guide

1. What is the "Custom (OpenAI Protocol)"？

The Custom (OpenAI Protocol) allows you to connect to the AI model of your choice, rather than relying solely on OpenAI’s official services. Through this feature, you can connect to locally deployed AI models or third-party API services that are compatible with the OpenAI format.

Local models： For example, if you’re running an AI model like llama.cpp or Oobabooga on your computer, you can use their API to interact with Tavo.

Third-party API services： For example, services like Cloudflare AI or DeepSeek that offer OpenAI-compatible APIs can also be easily integrated with Tavo.

2. Why use the "Custom (OpenAI Protocol)"?

◼️ Flexibility in Model Selection

You’re no longer restricted to OpenAI’s official models; you can select any compatible model for your conversations.

◼️ Cost Savings with Local Deployment

Running AI models locally means you avoid paying for cloud services, reducing costs.

◼️ Privacy Protection

You don’t need to send your conversation data to external servers, safeguarding your privacy.

◼️ Custom Optimization

Some models are optimized for specific tasks. Using these specialized models can lead to better results.

Example Scenario

Imagine you have deployed an AI model using llama.cpp on your computer, and the API endpoint is http://localhost:8080/v1. Using Tavo’s Custom (OpenAI Protocol), you can directly interact with this model without needing OpenAI's official services.

3. Quick Start Guide for Beginners

Step 1: Prepare the API Service

Local Deployment： You need to run a locally deployed OpenAI-compatible AI model (e.g., llama.cpp). Make sure the service is running and accessible via the URL http://localhost:1234/v1.

Third-party Service： If you are using a third-party API (e.g., DeepSeek or Cloudflare), obtain the API endpoint and API key from the service provider.

Step 2: Configure in Tavo

1.Open the settings page

In Tavo, click the button in the top left corner → API Connections → New Connection

2.Choose "Custom (OpenAI Protocol)"

In the "Model Platform" dropdown, select Custom (OpenAI Protocol)

3.Enter the API Key (if required)

If your service requires authentication, enter the corresponding API key; if not, you can leave it blank.

4.Fill in the API Base URL

Enter the API service address (e.g., http://localhost:1234/v1).

Note: If the connection fails, try appending /v1 to the URL and ensure the address format is correct.

5.Choose the Model Name

Refer to the service documentation to select the correct model name, such as gpt-4o or llama3-8b.

Step 3: Test and Begin Using

1.Send a Test Message

Set this API as the default and initiate a conversation with your character to check if the AI responds.

✅ Success Example： Receive a reply from the AI.

❌ Failure Handling： If there’s no response, check the URL, port, and model name to ensure everything is correct, and that the service is running.

2.Start Using

Once the configuration is successful, you can interact with your custom AI model in Tavo just like you would with OpenAI.

4. Common Issues

◼️ What to do if the connection fails?

Ensure that the API service is running (e.g., local services must be started).

Check if the API URL format is correct and if the port is open.

◼️ What if the model name is incorrect?

Make sure the model name matches the documentation provided by the service provider.

For local deployments, the model name usually matches the name of the model file you’ve loaded.

◼️ What to do if it shows “No Connection”?

Ensure that the API endpoint you configured is available, or double-check the endpoint configuration.

5. Prompt Handling and Compatibility

◼️ Compatibility Note

Although Tavo supports many API endpoints, it doesn't guarantee compatibility with all of them. Some local endpoints, such as TabbyAPI, Oobabooga, or Aphrodite, might require additional configuration. You may need to consult the documentation for these endpoints to ensure their API format complies with OpenAI’s standards.

Tip: If you can’t connect, try appending /v1 to the URL instead of /chat/completions, which usually resolves connection issues.

◼️ Prompt Format Limitations

Some endpoints may have specific prompt format requirements, such as only allowing one system message or requiring strict role alternation. Tavo offers built-in prompt converters to help you meet these requirements:

Merge consecutive messages from the same character (for loose format requirements)

Allow only one system message (for semi-strict requirements)

Allow one optional system message, requiring the user role to be the first (for strict requirements)

6. Example Configurations

◼️ Local Deployment with llama.cpp*

Custom Endpoint: http://localhost:8080/v1

Model Name: llama3-8b

API Key: Not required (local services typically don't need a key).

◼️ Third-party Service (DeepSeek)

Custom Endpoint: https://api.deepseek.com/v1

Model Name: deepseek-chat

API Key: Enter the key provided by the service.

OpenRouter API

First, register an account on the OpenRouter platform. Second, create and securely store your API key within your account.

API Settings

Or open the APP and click the "Menu Bar"