package com.snapspend;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ProcessReceiptHandler implements RequestHandler<S3Event, Void> {

    private final S3Client s3Client = S3Client.builder().build();
    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder().build();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();

    @Override
    public Void handleRequest(S3Event s3Event, Context context) {
        try {
            String bucket = s3Event.getRecords().get(0).getS3().getBucket().getName();
            String key = s3Event.getRecords().get(0).getS3().getObject().getKey();

            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            String base64Image = Base64.getEncoder().encodeToString(objectBytes.asByteArray());

            String promptJson = """
                            {
                                "anthropic_version": "bedrock-2023-05-31",
                                "max_tokens": 500,
                                "messages": [
                                    {
                                        "role": "user",
                                        "content": [
                                            {
                                                "type": "image",
                                                "source": {
                                                    "type": "base64",
                                                    "media_type": "image/jpeg",
                                                    "data": "%s"
                                                }
                                            },
                                            {
                                                "type": "text",
                                                "text": "Analyze this image (which may be a physical receipt or a digital payment screenshot from PhonePe, Google Pay, Paytm, bank transfer, etc.).\\n\\nExtract the following fields and return ONLY a valid, raw JSON object with no markdown backticks or extra text:\\n{\\n  \\"vendor\\": \\"recipient/merchant/sender name or UPI ID\\",\\n  \\"amount\\": \\"numeric value only without currency symbols, e.g. 150.00\\",\\n  \\"date\\": \\"YYYY-MM-DD (use today's date if year is missing)\\",\\n  \\"category\\": \\"Groceries | Food & Dining | Travel | Shopping | Bills | Transfer | Other\\",\\n  \\"type\\": \\"DEBIT or CREDIT\\"\\n}"
                                            }
                                        ]
                                    }
                                ]
                            }
                        """.formatted(base64Image);

            InvokeModelResponse response = bedrockClient.invokeModel(InvokeModelRequest.builder()
                    .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(promptJson))
                    .build());

            context.getLogger().log("Bedrock Response: " + response.body().asUtf8String());

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("receiptId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
            item.put("vendor", AttributeValue.builder().s("Pending Extraction").build());
            item.put("amount", AttributeValue.builder().s("0.00").build());

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName("Expenses")
                    .item(item)
                    .build());

        } catch (Exception e) {
            context.getLogger().log("Error processing receipt: " + e.getMessage());
        }
        return null;
    }
}

