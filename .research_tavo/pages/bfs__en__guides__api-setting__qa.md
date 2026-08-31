URL: https://docs.tavoai.dev/en/guides/api-setting/qa/
STATUS: 200

Guide
API & Models

Copy Page

Common Errors and Solutions

Usage Instructions

This page summarizes common API issues encountered by users when using Tavo, along with corresponding solutions. If you encounter a problem, we recommend referring to the guidance below first. You can search for error keywords or quickly locate the issue based on error codes. If the problem persists, please contact our official customer support for further assistance.

We hope this guide helps you!

Common Issue Categories and Solutions

I. API-Related Issues (Continuously Updated)

1. Invalid API Key

Solution:

Ensure the key characters are completely correct (case-sensitive).

Check for leading or trailing spaces (it is recommended to paste the key into a text editor for verification).

Confirm that the key has not been revoked or expired.

Verify that the selected model platform is correct.

2. Geographical Restrictions

Solution:

Some models (such as OpenAI, Claude, Gemini, etc.) restrict IP access from mainland China and Hong Kong. If you encounter this issue, check whether your current network environment meets the model platform's requirements, or try switching to a legitimate network node.

3. Model Not Activated

Solution:

Some models require manual permission activation in the corresponding platform backend before use. For example, the Doubao model requires an activation operation in the backend.

4. Model Busy

Solution:

When prompted with "Model Busy," it typically indicates that the model platform's server is temporarily under high load (e.g., due to official server maintenance or temporary failures). We recommend trying again later or checking the platform's status announcements for real-time updates.

5. Other Errors

If you encounter unknown errors or issues not covered above, resolve them as follows:

Verify that your operational steps comply with the official documentation requirements.

Provide detailed feedback about the issue to the official customer support email to receive professional assistance and solutions.

Include complete error screenshots to help the technical team quickly identify the problem.

Note: As features are iterated, this page will continue to be updated with more common issues. If your issue is not listed here, please contact us at [email&#160;protected].

II. Common Model Return Errors and Solutions

1. "Resource Exhausted" Error

Cause: Check whether the account balance is insufficient.

Solution: Review and recharge the account balance.

2. API Connection Issues

Cause: Likely an API-related problem.

Solution: Try switching to another API.

3. Empty Model Response

Cause: The model returned an empty response.

Solution: Try disabling streaming transmission in the model settings.

4. Free Quota Exhausted

Cause: The model has message restrictions, and the daily free quota has been used up.

Solution: Wait for the quota to reset or upgrade to a paid plan.

5. Request Size Exceeded

Cause: The size of the sent request exceeds the maximum limit, likely due to a single overly large message.

Solution: Reduce the memory context length.

6. Content Filtered

Cause: The model returned an empty response, likely because the returned content was filtered by the platform or the model declined to respond to the chat content.

Solution: Adjust the chat content to avoid sensitive topics.

7. Sensitive Information Detected

Cause: The chat content may contain sensitive information disliked by the model.

Solution: Check and modify sensitive information in the chat content.

Troubleshooting Guide

If your error is not listed above or remains unresolved after trying the suggested methods, we recommend the following:

Carefully review your operational steps and input parameters to ensure no omissions or errors.

Consult relevant documentation or tutorials, which may provide more detailed explanations. If you encounter an issue, click on the error details, translate the error message, and search online for related documentation or tutorials for further insights.

Seek help in the community:

Click the Discord community link below and enter the relevant channel to ask questions. Provide a detailed description of your issue, the methods you've tried, and specific error information. Community members may have helpful suggestions!

Discord Community Link: https://discord.gg/47cBNpQDFG

Model Settings

This page guides you on how to view and switch between available AI models, as well as how to configure and adjust model parameters.

Role

Open the app and click the blue "Character" button.